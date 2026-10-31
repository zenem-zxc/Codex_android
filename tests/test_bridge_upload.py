import base64
import hashlib
import tempfile
import unittest
from pathlib import Path

from pc_bridge.bridge import Bridge, MAX_UPLOAD_BYTES


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


if __name__ == "__main__":
    unittest.main()
