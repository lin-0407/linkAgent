"""与 Java 共用固定摘要向量，避免小数表示差异在真正出片前才暴露。"""

import json
import unittest

from linkagent_renderer.contract import fingerprint


class FingerprintTest(unittest.TestCase):
    def test_shared_java_numeric_vectors(self):
        vectors = [
            ("1", "7c2768966af9de0160206f5aac5bff4dc4825c781323b7974cb566b9ee8e0eff"),
            ("1.0", "3fda219902378725d7d52d4969b58c77c06618cc1159b154f814fbefb02efc03"),
            ("-0.0", "ed1714b813573d19b6094d4a3566b7970dfe04a082f572731ec7a99d1e59bf55"),
            ("-0.0001", "a4de44a59e32475fa00d5aec29a20acf3b9345eee722a3cce5c5e8d2dfd99d82"),
            ("-0.1", "d42f706842d3abddddc4d3b086e34a0313d274dafe3e3ca134dfdb2f28a29d45"),
            ("-1e-6", "11e36a5dd65f056abadd139af5e78f9de2a26073c5aee85ff9101fcfc9754932"),
            ("-1e-300", "7456c88f32a389dfd327b55f12e245c9d4174218252468e5afd23a18ef178094"),
            ("-5e-324", "11721a3b8ed1af8557dd261ce5451463d3fae29b62846e651cf7ace87dc11ad0"),
        ]
        for number, expected in vectors:
            with self.subTest(number=number):
                self.assertEqual(expected, fingerprint(json.loads('{"gainDb":' + number + '}')))

    def test_non_finite_values_are_rejected(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    fingerprint({"gainDb": value})

    def test_user_text_cannot_collide_with_floating_token(self):
        self.assertNotEqual(fingerprint({"gainDb": -0.0001}), fingerprint({"gainDb": "f64:bf1a36e2eb1c432d"}))

    def test_integer_project_preserves_existing_fingerprint(self):
        import hashlib
        document = {"gainDb": -3, "title": "中文\n引号\"", "flags": [True, None]}
        expected = hashlib.sha256(json.dumps(document, ensure_ascii=False, sort_keys=True,
                                            separators=(",", ":")).encode("utf-8")).hexdigest()
        self.assertEqual(expected, fingerprint(document))
