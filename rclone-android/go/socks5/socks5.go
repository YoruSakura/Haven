// Package socks5 is a minimal RFC 1928 SOCKS5 server used by wgbridge
// and tsbridge to expose their tunnels to local clients that speak SOCKS
// (rclone, IronRDP, anything with HTTPS_PROXY support).
//
// Only the bits the bridges need are implemented:
//   - METHOD-NEG with no-auth (0x00) only.
//   - CONNECT command only (no BIND, no UDP ASSOCIATE).
//   - IPv4, IPv6, and DOMAIN address types.
//
// The package is intentionally not exposed via gomobile — wgbridge /
// tsbridge import it transitively.
package socks5

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
)

// Dialer is the upstream dial function used to establish the tunneled
// connection after a SOCKS5 CONNECT request is parsed. host is the
// caller-supplied target name (which may be a literal IP).
type Dialer func(host string, port int) (net.Conn, error)

// Serve accepts SOCKS5 client connections on ln, performs the CONNECT
// handshake, and pipes bytes between client and the upstream connection
// returned by dialer. Each accepted connection is handled in its own
// goroutine. Returns when ln.Accept returns an error (typically because
// ln has been closed by the caller).
func Serve(ln net.Listener, dialer Dialer) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handle(c, dialer)
	}
}

func handle(client net.Conn, dialer Dialer) {
	defer client.Close()
	if err := negotiate(client); err != nil {
		return
	}
	host, port, err := readConnect(client)
	if err != nil {
		writeReply(client, repGeneralFailure)
		return
	}
	upstream, err := dialer(host, port)
	if err != nil {
		writeReply(client, repConnectionRefused)
		return
	}
	defer upstream.Close()
	if err := writeReply(client, repSuccess); err != nil {
		return
	}
	pipe(client, upstream)
}

// SOCKS5 reply codes (RFC 1928 §6).
const (
	repSuccess           = 0x00
	repGeneralFailure    = 0x01
	repConnectionRefused = 0x05
)

// negotiate handles METHOD-NEG: pick no-auth (0x00) if the client offers it.
func negotiate(c net.Conn) error {
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		return err
	}
	if hdr[0] != 0x05 {
		return errors.New("socks5: not version 5")
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return err
	}
	for _, m := range methods {
		if m == 0x00 {
			_, err := c.Write([]byte{0x05, 0x00})
			return err
		}
	}
	c.Write([]byte{0x05, 0xFF}) // no acceptable methods
	return errors.New("socks5: no acceptable auth method")
}

// readConnect parses a CONNECT request and returns (host, port).
func readConnect(c net.Conn) (string, int, error) {
	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return "", 0, err
	}
	if req[0] != 0x05 {
		return "", 0, errors.New("socks5: not version 5")
	}
	if req[1] != 0x01 {
		return "", 0, errors.New("socks5: only CONNECT is supported")
	}
	var host string
	switch req[3] {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(ip[:]).String()
	case 0x03: // DOMAIN
		var l [1]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return "", 0, err
		}
		name := make([]byte, l[0])
		if _, err := io.ReadFull(c, name); err != nil {
			return "", 0, err
		}
		host = string(name)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(c, ip[:]); err != nil {
			return "", 0, err
		}
		host = net.IP(ip[:]).String()
	default:
		return "", 0, errors.New("socks5: unsupported address type")
	}
	var p [2]byte
	if _, err := io.ReadFull(c, p[:]); err != nil {
		return "", 0, err
	}
	return host, int(binary.BigEndian.Uint16(p[:])), nil
}

// writeReply sends a SOCKS5 reply with BND.ADDR=0.0.0.0 and BND.PORT=0.
// Real bind addresses don't matter for CONNECT clients.
func writeReply(c net.Conn, status byte) error {
	_, err := c.Write([]byte{0x05, status, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

// pipe copies bytes in both directions until either side closes. Closes
// nothing itself — caller owns both connections.
func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(a, b); done <- struct{}{} }()
	go func() { _, _ = io.Copy(b, a); done <- struct{}{} }()
	<-done
}
