"""Metadata tests with public test PKI and standard EC parameter fixtures; no network or third-party packages."""
import base64
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("pkd_profile", ROOT / "tools/pkd_profile.py")
pkd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pkd)


def tlv(tag, value):
    length = len(value)
    size = (length.bit_length() + 7) // 8
    encoded_length = bytes([length]) if length < 128 else bytes([0x80 | size]) + length.to_bytes(size, "big")
    return bytes([tag]) + encoded_length + value


def explicit_spki(curve):
    params = (Path(__file__).parent / "fixtures" / f"{curve}.der").read_bytes()
    algorithm = tlv(0x30, bytes.fromhex("06072a8648ce3d0201") + params)
    # Key bytes aren't used for curve recognition; these fixtures test the domain parameters.
    spki = tlv(0x30, algorithm + tlv(0x03, b"\0\x04"))
    _, start, end = pkd.read_tlv(spki)
    return pkd.describe_key(spki, start, end)


class PkdMetadataTest(unittest.TestCase):
    def test_explicit_curves_require_all_domain_parameters(self):
        expected = {"prime256v1": "P-256", "secp384r1": "P-384", "secp521r1": "P-521",
                    **{f"brainpoolP{bits}r1": f"brainpoolP{bits}r1" for bits in (256, 320, 384, 512)}}
        for curve, name in expected.items():
            with self.subTest(curve=curve):
                self.assertEqual(explicit_spki(curve), f"EC {name} (explicit)")
        self.assertEqual(explicit_spki("brainpoolP256t1"), "EC unrecognised 256-bit curve (explicit)")

    def test_standalone_ca_is_not_mistaken_for_a_document_signer(self):
        for relative, is_ca in [("csca/smartemu-test-csca.cert.pem", True),
                                ("document-signer/smartemu-test-ds.cert.pem", False)]:
            records = list(pkd.read_certificates(ROOT / "test-pki" / relative))
            self.assertEqual(records[0][0], "CERT")
            self.assertEqual(pkd.parse_certificate(records[0][1])["is_ca"], is_ca)

    def test_ldif_folds_and_kind_are_preserved(self):
        _, der = next(pkd.read_certificates(ROOT / "test-pki/document-signer/smartemu-test-ds.cert.pem"))
        encoded = base64.b64encode(der).decode("ascii")
        text = "dn: test\nuserCertificate;binary:: " + encoded[:40] + "\n " + encoded[40:] + "\n\n"
        entry = next(pkd.ldif_entries(text))
        self.assertEqual(base64.b64decode(entry[1][1]), der)
        self.assertTrue(entry[1][2])

    def test_malformed_der_and_oids_fail_cleanly(self):
        for data in (b"\x1f\x80", b"\x30\x82\x01", b"\x30\x80", b"\x30\x04\x01"):
            with self.subTest(data=data), self.assertRaises(pkd.DerError):
                pkd.read_tlv(data)
        for oid in (b"", b"\x2a\x80"):
            with self.assertRaises(pkd.DerError):
                pkd.decode_oid(oid)
        with self.assertRaises(pkd.DerError):
            pkd.children(b"\x04\x02xx", 0, 2)


if __name__ == "__main__":
    unittest.main()
