package sstp

// Async HDLC-like framing (RFC 1662) between SSTP data packets (raw PPP frames) and pppd,
// which runs in "notty" mode and talks async PPP over its stdin/stdout.

var fcsTab [256]uint16

func init() {
	for b := 0; b < 256; b++ {
		v := uint16(b)
		for i := 0; i < 8; i++ {
			if v&1 != 0 {
				v = (v >> 1) ^ 0x8408
			} else {
				v >>= 1
			}
		}
		fcsTab[b] = v
	}
}

func fcs16(fcs uint16, data []byte) uint16 {
	for _, b := range data {
		fcs = (fcs >> 8) ^ fcsTab[(fcs^uint16(b))&0xff]
	}
	return fcs
}

const (
	flagSeq   = 0x7e
	escapeSeq = 0x7d
	goodFCS   = 0xf0b8
	maxFrame  = 8192
)

// hdlcEncode appends the escaped, FCS-terminated frame to dst.
func hdlcEncode(dst, frame []byte) []byte {
	fcs := ^fcs16(0xffff, frame)
	dst = append(dst, flagSeq)
	put := func(b byte) {
		if b < 0x20 || b == flagSeq || b == escapeSeq {
			dst = append(dst, escapeSeq, b^0x20)
		} else {
			dst = append(dst, b)
		}
	}
	for _, b := range frame {
		put(b)
	}
	put(byte(fcs))
	put(byte(fcs >> 8))
	return append(dst, flagSeq)
}

// hdlcDecoder reassembles frames from an async PPP byte stream.
type hdlcDecoder struct {
	buf    []byte
	escape bool
}

// feed processes data and calls emit for every complete frame with a valid FCS (FCS stripped).
func (d *hdlcDecoder) feed(data []byte, emit func([]byte)) {
	for _, b := range data {
		switch {
		case b == flagSeq:
			if len(d.buf) >= 4 && fcs16(0xffff, d.buf) == goodFCS {
				frame := make([]byte, len(d.buf)-2)
				copy(frame, d.buf)
				emit(frame)
			}
			d.buf = d.buf[:0]
			d.escape = false
		case b == escapeSeq:
			d.escape = true
		default:
			if d.escape {
				b ^= 0x20
				d.escape = false
			}
			if len(d.buf) < maxFrame {
				d.buf = append(d.buf, b)
			}
		}
	}
}
