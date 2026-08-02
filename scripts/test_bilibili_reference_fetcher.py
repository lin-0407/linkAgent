import unittest
from unittest.mock import patch

from scripts import bilibili_reference_fetcher as fetcher


class BilibiliReferenceFetcherTest(unittest.TestCase):
    def test_normalize_cover_url_upgrades_public_bilibili_cdn_urls(self):
        self.assertEqual(
            "https://i0.hdslb.com/bfs/archive/cover.jpg",
            fetcher.normalize_cover_url("//i0.hdslb.com/bfs/archive/cover.jpg"),
        )
        self.assertEqual(
            "https://i1.hdslb.com/bfs/archive/cover.jpg",
            fetcher.normalize_cover_url("http://i1.hdslb.com/bfs/archive/cover.jpg"),
        )

    def test_normalize_cover_url_rejects_untrusted_or_temporary_urls(self):
        invalid_urls = (
            "https://i0.hdslb.com.example.com/bfs/archive/cover.jpg",
            "https://example.com/cover.jpg",
            "ftp://i0.hdslb.com/bfs/archive/cover.jpg",
            "https://i0.hdslb.com:443/bfs/archive/cover.jpg",
            "https://i0.hdslb.com/bfs/archive/cover.jpg?Expires=123",
            "https://i0.hdslb.com/bfs/archive/cover.jpg?x-oss-signature=abc",
            "https://i0.hdslb.com/bfs/archive/cover.jpg?security-token=abc",
            "https://i0.hdslb.com/bfs/archive/cover.jpg?wsSecret=abc",
        )
        for url in invalid_urls:
            with self.subTest(url=url):
                self.assertIsNone(fetcher.normalize_cover_url(url))

    def test_build_video_item_outputs_cover_and_six_public_stats(self):
        video_info = {
            "bvid": "BV1xx411c7mD",
            "title": "测试视频",
            "desc": "简介",
            "pic": "//i2.hdslb.com/bfs/archive/cover.jpg",
            "tname": "知识",
            "pubdate": 1_700_000_000,
            "stat": {
                "view": 100,
                "like": 20,
                "coin": 5,
                "favorite": 8,
                "danmaku": 3,
                "reply": 6,
            },
        }
        options = fetcher.FetchOptions(
            source="manual_bv",
            tier=None,
            category=None,
            max_comments=0,
            max_danmaku=0,
            comment_page_size=20,
            comment_sort=1,
            delay=0,
            timeout=15,
        )

        with (
            patch.object(fetcher, "fetch_video_info", return_value=video_info),
            patch.object(fetcher, "collect_root_comments", return_value=[]),
            patch.object(fetcher, "collect_danmaku", return_value=[]),
        ):
            item = fetcher.build_video_item("BV1xx411c7mD", options, [], {})

        self.assertEqual("https://i2.hdslb.com/bfs/archive/cover.jpg", item["coverUrl"])
        self.assertEqual(video_info["stat"], item["stats"])
        self.assertEqual([], item["comments"])
        self.assertEqual([], item["danmaku"])


if __name__ == "__main__":
    unittest.main()
