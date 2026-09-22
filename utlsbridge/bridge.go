// Package utlsbridge exposes a loopback-only TLS bridge to Android.
package utlsbridge

import (
	"bufio"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
)

type server struct {
	listener     net.Listener
	upstreamPort int
	sni          string
	password     string
	alpn         []string
	closed       chan struct{}
	once         sync.Once
	wg           sync.WaitGroup
	connections  sync.Map
}

var (
	activeMu sync.Mutex
	active   *server
)

// Start starts a loopback bridge and returns its TCP port.
func Start(upstreamPort int, sni, password, alpnCSV string) (int, error) {
	if upstreamPort < 1 || upstreamPort > 65535 || sni == "" || password == "" {
		return 0, errors.New("invalid Trojan bridge configuration")
	}

	activeMu.Lock()
	defer activeMu.Unlock()
	if active != nil {
		return 0, errors.New("Trojan TLS bridge is already running")
	}
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	s := &server{
		listener: listener, upstreamPort: upstreamPort,
		sni: sni, password: password, alpn: splitALPN(alpnCSV), closed: make(chan struct{}),
	}
	active = s
	s.wg.Add(1)
	go s.acceptLoop()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

// Stop stops the active bridge and all future accepts.
func Stop() {
	activeMu.Lock()
	s := active
	active = nil
	activeMu.Unlock()
	if s == nil {
		return
	}
	s.once.Do(func() {
		close(s.closed)
		_ = s.listener.Close()
		s.connections.Range(func(key, _ any) bool {
			_ = key.(net.Conn).Close()
			return true
		})
	})
	s.wg.Wait()
}

func splitALPN(value string) []string {
	var result []string
	for _, token := range strings.Split(value, ",") {
		if token = strings.TrimSpace(token); token != "" {
			result = append(result, token)
		}
	}
	return result
}

func (s *server) acceptLoop() {
	defer s.wg.Done()
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.handle(conn)
		}()
	}
}

func (s *server) handle(local net.Conn) {
	s.connections.Store(local, struct{}{})
	defer s.connections.Delete(local)
	defer local.Close()
	_ = local.SetDeadline(time.Now().Add(15 * time.Second))
	reader := bufio.NewReader(local)
	command, destination, initial, err := readRequest(reader)
	if err != nil {
		writeFailure(local, err)
		return
	}

	remote, err := s.dialUTLS()
	if err != nil {
		writeFailure(local, err)
		return
	}
	s.connections.Store(remote, struct{}{})
	defer s.connections.Delete(remote)
	defer remote.Close()

	request := make([]byte, 0, 64+len(destination)+len(initial))
	hash := sha256.Sum224([]byte(s.password))
	request = append(request, hex.EncodeToString(hash[:])...)
	request = append(request, '\r', '\n', command)
	request = append(request, destination...)
	request = append(request, '\r', '\n')
	request = append(request, initial...)
	if _, err := remote.Write(request); err != nil {
		writeFailure(local, fmt.Errorf("Trojan request failed: %w", err))
		return
	}
	if _, err := local.Write([]byte{0}); err != nil {
		return
	}
	_ = local.SetDeadline(time.Time{})

	done := make(chan struct{}, 1)
	go func() {
		_, _ = io.Copy(remote, reader)
		if tcp, ok := remote.NetConn().(*net.TCPConn); ok {
			_ = tcp.CloseWrite()
		}
		done <- struct{}{}
	}()
	_, _ = io.Copy(local, remote)
	_ = local.Close()
	<-done
}

func (s *server) dialUTLS() (*utls.UConn, error) {
	raw, err := net.DialTimeout("tcp4", fmt.Sprintf("127.0.0.1:%d", s.upstreamPort), 10*time.Second)
	if err != nil {
		return nil, fmt.Errorf("server TCP connect failed: %w", err)
	}
	tlsConn := utls.UClient(raw, &utls.Config{
		ServerName: s.sni,
		NextProtos: s.alpn,
		MinVersion: utls.VersionTLS12,
	}, utls.HelloChrome_Auto)
	_ = tlsConn.SetDeadline(time.Now().Add(15 * time.Second))
	if err := tlsConn.Handshake(); err != nil {
		_ = raw.Close()
		return nil, fmt.Errorf("Chrome TLS handshake failed: %w", err)
	}
	_ = tlsConn.SetDeadline(time.Time{})
	return tlsConn, nil
}

func readRequest(reader *bufio.Reader) (byte, []byte, []byte, error) {
	magic := make([]byte, 4)
	if _, err := io.ReadFull(reader, magic); err != nil || string(magic) != "OTB1" {
		return 0, nil, nil, errors.New("invalid bridge request")
	}
	command, err := reader.ReadByte()
	if err != nil || (command != 1 && command != 3) {
		return 0, nil, nil, errors.New("invalid Trojan command")
	}
	atyp, err := reader.ReadByte()
	if err != nil {
		return 0, nil, nil, err
	}
	destination := []byte{atyp}
	switch atyp {
	case 1:
		value := make([]byte, 6)
		if _, err = io.ReadFull(reader, value); err != nil {
			return 0, nil, nil, err
		}
		destination = append(destination, value...)
	case 3:
		length, lengthErr := reader.ReadByte()
		if lengthErr != nil || length == 0 {
			return 0, nil, nil, errors.New("invalid destination domain")
		}
		value := make([]byte, int(length)+2)
		if _, err = io.ReadFull(reader, value); err != nil {
			return 0, nil, nil, err
		}
		destination = append(destination, length)
		destination = append(destination, value...)
	default:
		return 0, nil, nil, errors.New("IPv6 is disabled")
	}
	var length uint32
	if err := binary.Read(reader, binary.BigEndian, &length); err != nil || length > 1<<20 {
		return 0, nil, nil, errors.New("invalid initial payload")
	}
	initial := make([]byte, int(length))
	if _, err := io.ReadFull(reader, initial); err != nil {
		return 0, nil, nil, err
	}
	return command, destination, initial, nil
}

func writeFailure(conn net.Conn, err error) {
	message := []byte(err.Error())
	if len(message) > 1024 {
		message = message[:1024]
	}
	response := []byte{1, byte(len(message) >> 8), byte(len(message))}
	response = append(response, message...)
	_, _ = conn.Write(response)
}
