package utlsbridge

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"testing"
)

func TestReadRequest(t *testing.T) {
	var data bytes.Buffer
	data.WriteString("OTB1")
	data.WriteByte(1)
	data.WriteByte(3)
	data.WriteByte(11)
	data.WriteString("example.com")
	_ = binary.Write(&data, binary.BigEndian, uint16(443))
	_ = binary.Write(&data, binary.BigEndian, uint32(3))
	data.WriteString("abc")

	command, destination, initial, err := readRequest(bufio.NewReader(&data))
	if err != nil {
		t.Fatal(err)
	}
	if command != 1 || len(destination) != 15 || string(initial) != "abc" {
		t.Fatalf("unexpected request: command=%d destination=%x initial=%q", command, destination, initial)
	}
}

func TestSplitALPN(t *testing.T) {
	got := splitALPN(" h2, http/1.1 ,, ")
	if len(got) != 2 || got[0] != "h2" || got[1] != "http/1.1" {
		t.Fatalf("unexpected ALPN: %#v", got)
	}
}
