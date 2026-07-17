import unittest
from unittest.mock import patch

from scripts import bilibili_creator_videos_fetcher as fetcher


class FakeResponse:
    def __init__(self, body: bytes):
        self.body = body
        self.headers = {}

    def __enter__(self):
        return self

    def __exit__(self, exception_type, exception_value, traceback):
        return False

    def read(self) -> bytes:
        return self.body


class BilibiliCreatorVideosFetcherTest(unittest.TestCase):
    @patch.object(fetcher, "urlopen")
    def test_fetch_recent_videos_uses_creator_space_referer(self, mocked_urlopen):
        mocked_urlopen.return_value = FakeResponse(
            b'{"code":0,"data":{"page":{"count":0},"list":{"vlist":[]}}}'
        )

        videos, has_more = fetcher.fetch_recent_videos(
            "27058248",
            1,
            fetcher.WbiSigner("test-mixin-key"),
            15,
        )

        request = mocked_urlopen.call_args.args[0]

        self.assertEqual([], videos)
        self.assertFalse(has_more)
        self.assertEqual(
            "https://space.bilibili.com/27058248/",
            request.get_header("Referer"),
        )
