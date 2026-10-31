import base64
import hashlib
import tempfile
import unittest
from pathlib import Path

from pc_bridge.bridge import Bridge, MAX_UPLOAD_BYTES, MAX_STREAM_UPLOAD_BYTES


class UploadTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.bridge = Bridge("unused", "secret", self.directory)

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_file_saved_with_generated_name_and_correct_hash(self):
        data = b"\x00fake image data"
        response = await self.bridge.handle_upload({
            "id": 7,
            "params": {
                "fileName": "../../private/photo.JPG",
                "mimeType": "image/jpeg",
                "dataBase64": base64.b64encode(data).decode("ascii"),
            },
        })
        result = response["result"]
        saved = Path(result["path"])
        self.assertEqual(saved.parent, self.directory)
        self.assertEqual(saved.suffix, ".jpg")
        self.assertNotEqual(saved.name, "photo.jpg")
        self.assertEqual(saved.read_bytes(), data)
        self.assertEqual(result["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(result["fileName"], "photo.JPG")

    async def test_rejects_invalid_base64_and_missing_id(self):
        for message in [
            {"id": 1, "params": {"dataBase64": "!!!"}},
            {"params": {"dataBase64": "YQ=="}},
            {"id": 2, "params": {"dataBase64": ""}},
            {"id": 3, "params": []},
        ]:
            response = await self.bridge.handle_upload(message)
            self.assertIn("error", response)
        self.assertEqual(list(self.directory.iterdir()), [])

    async def test_rejects_oversized_before_writing(self):
        response = await self.bridge.handle_upload({
            "id": 1,
            "params": {"dataBase64": "A" * ((((MAX_UPLOAD_BYTES + 2) // 3) * 4) + 4)},
        })
        self.assertIn("error", response)
        self.assertEqual(list(self.directory.iterdir()), [])

    async def test_windows_traversal_cannot_choose_destination(self):
        response = await self.bridge.handle_upload({
            "id": "test",
            "params": {"fileName": r"C:\outside\test.pdf", "dataBase64": "YQ=="},
        })
        self.assertEqual(Path(response["result"]["path"]).parent, self.directory)
        self.assertEqual(response["result"]["fileName"], "test.pdf")

    async def test_streamed_upload_and_size_validation(self):
        uploads = {}
        def send(method, params):
            return self.bridge.handle_stream_upload({"id": 1, "method": method, "params": params}, uploads)
        for size in [0, MAX_STREAM_UPLOAD_BYTES + 1, True]:
            self.assertIn("error", send("bridge/upload/start", {"fileName": "x.txt", "size": size}))
        started = send("bridge/upload/start", {"fileName": "../test.txt", "size": 6})
        upload_id = started["result"]["uploadId"]
        self.assertIn("error", send("bridge/upload/finish", {"uploadId": upload_id}))
        for chunk in [b"abc", b"def"]:
            self.assertIn("result", send("bridge/upload/chunk", {
                "uploadId": upload_id, "dataBase64": base64.b64encode(chunk).decode(),
            }))
        finished = send("bridge/upload/finish", {"uploadId": upload_id})["result"]
        self.assertEqual(Path(finished["path"]).read_bytes(), b"abcdef")
        self.assertEqual(finished["sha256"], hashlib.sha256(b"abcdef").hexdigest())
        self.assertFalse(uploads)

    async def test_stream_cancel_removes_partial_file(self):
        uploads = {}
        started = self.bridge.handle_stream_upload({"id": 1, "method": "bridge/upload/start", "params": {"fileName": "x", "size": 5}}, uploads)
        upload_id = started["result"]["uploadId"]
        result = self.bridge.handle_stream_upload({"id": 2, "method": "bridge/upload/cancel", "params": {"uploadId": upload_id}}, uploads)
        self.assertTrue(result["result"]["cancelled"])
        self.assertEqual(list(self.directory.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
