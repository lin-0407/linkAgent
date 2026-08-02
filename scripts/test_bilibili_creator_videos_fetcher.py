import argparse
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
    def test_fetch_account_profile_returns_avatar(self, mocked_urlopen):
        mocked_urlopen.return_value = FakeResponse(
            b'{"code":0,"data":{"mid":27058248,"name":"LinkAgent","face":"http://i0.hdslb.com/bfs/face/avatar.jpg"}}'
        )

        nickname, avatar_url = fetcher.fetch_account_profile(
            "27058248",
            fetcher.WbiSigner("test-mixin-key"),
            15,
            [],
        )

        self.assertEqual("LinkAgent", nickname)
        self.assertEqual("https://i0.hdslb.com/bfs/face/avatar.jpg", avatar_url)

    @patch.object(fetcher, "fetch_recent_videos", return_value=([], False))
    @patch.object(
        fetcher,
        "fetch_account_profile",
        return_value=("LinkAgent", "https://i0.hdslb.com/bfs/face/avatar.jpg"),
    )
    @patch.object(fetcher, "get_wbi_signer", return_value=fetcher.WbiSigner("test-mixin-key"))
    def test_collect_payload_includes_avatar(self, _signer, _profile, _videos):
        payload = fetcher.collect_payload(argparse.Namespace(
            uid="27058248",
            max_videos=1,
            target_bvid=[],
            timeout=15,
        ))

        self.assertEqual("https://i0.hdslb.com/bfs/face/avatar.jpg", payload["avatarUrl"])

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
