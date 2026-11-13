package socks5

import (
	"bytes"
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

// startEchoServer spins up a tiny TCP echo server on 127.0.0.1 and
// returns its address + a close hook.
func startEchoServer(t *testing.T) (host string, port int, close func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen echo: %v", err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go io.Copy(c, c)
		}
	}()
	addr := ln.Addr().(*net.TCPAddr)
	return "127.0.0.1", addr.Port, func() { ln.Close() }
}

// startSocks5 wraps an arbitrary dialer with our SOCKS5 server on a
// fresh 127.0.0.1 port and returns the bound port + close hook.
func startSocks5(t *testing.T, dialer Dialer) (port int, close func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen socks5: %v", err)
	}
	go Serve(ln, dialer)
	return ln.Addr().(*net.TCPAddr).Port, func() { ln.Close() }
}

// socks5Connect performs the minimal client side of CONNECT and returns
// the now-streaming connection. host and port name the upstream target.
func socks5Connect(t *testing.T, proxyPort int, atyp byte, host string, port int) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatalf("dial socks5: %v", err)
	}
	c.SetDeadline(time.Now().Add(2 * time.Second))

	// METHOD-NEG: ver, nmethods=1, methods={0x00 no-auth}
	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		t.Fatalf("write method-neg: %v", err)
	}
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		t.Fatalf("read method-neg reply: %v", err)
	}
	if hdr[0] != 0x05 || hdr[1] != 0x00 {
		t.Fatalf("expected version 5 + no-auth (0x00), got %x", hdr)
	}

	// CONNECT
	var req bytes.Buffer
	req.Write([]byte{0x05, 0x01, 0x00, atyp})
	switch atyp {
	case 0x01:
		ip := net.ParseIP(host).To4()
		if ip == nil {
			t.Fatalf("not an IPv4: %q", host)
		}
		req.Write(ip)
	case 0x03:
		req.WriteByte(byte(len(host)))
		req.WriteString(host)
	case 0x04:
		ip := net.ParseIP(host).To16()
		if ip == nil {
			t.Fatalf("not an IPv6: %q", host)
		}
		req.Write(ip)
	default:
		t.Fatalf("unsupported atyp %x", atyp)
	}
	binary.Write(&req, binary.BigEndian, uint16(port))
	if _, err := c.Write(req.Bytes()); err != nil {
		t.Fatalf("write CONNECT: %v", err)
	}
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("read CONNECT reply: %v", err)
	}
	if reply[1] != 0x00 {
		t.Fatalf("CONNECT failed, REP=0x%02x", reply[1])
	}
	c.SetDeadline(time.Time{})
	return c
}

func TestEndToEnd_DomainAtype(t *testing.T) {
	echoHost, echoPort, closeEcho := startEchoServer(t)
	defer closeEcho()

	dialer := func(host string, port int) (net.Conn, error) {
		return net.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	}
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	// The SOCKS5 client says "localhost" (DOMAIN atyp). Our dialer
	// resolves it to the echo server.
	_ = echoHost
	c := socks5Connect(t, proxyPort, 0x03, "localhost", echoPort)
	defer c.Close()

	c.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := c.Write([]byte("ping")); err != nil {
		t.Fatalf("write: %v", err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if string(buf) != "ping" {
		t.Fatalf("echo mismatch: %q", string(buf))
	}
}

func TestEndToEnd_IPv4Atype(t *testing.T) {
	echoHost, echoPort, closeEcho := startEchoServer(t)
	defer closeEcho()

	dialer := func(host string, port int) (net.Conn, error) {
		return net.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	}
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	c := socks5Connect(t, proxyPort, 0x01, echoHost, echoPort)
	defer c.Close()

	c.SetDeadline(time.Now().Add(2 * time.Second))
	c.Write([]byte("hi"))
	buf := make([]byte, 2)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if string(buf) != "hi" {
		t.Fatalf("echo mismatch: %q", string(buf))
	}
}

func TestDialerRefusal_ReportsConnectionRefused(t *testing.T) {
	dialer := func(host string, port int) (net.Conn, error) {
		return nil, io.ErrUnexpectedEOF // any error
	}
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(2 * time.Second))

	// METHOD-NEG → no-auth
	c.Write([]byte{0x05, 0x01, 0x00})
	hdr := make([]byte, 2)
	io.ReadFull(c, hdr)

	// CONNECT to 1.2.3.4:80
	c.Write([]byte{0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x00, 0x50})
	reply := make([]byte, 10)
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatalf("read CONNECT reply: %v", err)
	}
	if reply[1] != repConnectionRefused {
		t.Fatalf("expected REP=0x05 (refused), got 0x%02x", reply[1])
	}
}

func TestRejectsNonSocks5(t *testing.T) {
	dialer := func(host string, port int) (net.Conn, error) { return nil, io.EOF }
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(2 * time.Second))

	// SOCKS4-shaped greeting (ver=0x04). Server should hang up.
	c.Write([]byte{0x04, 0x01})
	buf := make([]byte, 1)
	_, err = io.ReadFull(c, buf)
	if err == nil {
		t.Fatalf("expected EOF on non-SOCKS5 greeting, got data: %x", buf)
	}
}

func TestRejectsNonNoAuth(t *testing.T) {
	dialer := func(host string, port int) (net.Conn, error) { return nil, io.EOF }
	proxyPort, closeSocks := startSocks5(t, dialer)
	defer closeSocks()

	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(2 * time.Second))

	// METHOD-NEG offering only USERNAME/PASSWORD (0x02) — not supported.
	c.Write([]byte{0x05, 0x01, 0x02})
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(c, hdr); err != nil {
		t.Fatalf("read method-neg reply: %v", err)
	}
	if hdr[0] != 0x05 || hdr[1] != 0xFF {
		t.Fatalf("expected version 5 + no-acceptable (0xFF), got %x", hdr)
	}
}
