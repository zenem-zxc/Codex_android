import asyncio
import unittest
from unittest.mock import AsyncMock, Mock

from pc_bridge.bridge import Bridge


class DisconnectTests(unittest.IsolatedAsyncioTestCase):
    async def test_reset_during_close_is_handled_and_slot_released(self):
        bridge = Bridge("unused", "secret")
        reader = Mock()
        reader.readline = AsyncMock(return_value=b"codex-bridge secret\n")
        writer = Mock()
        writer.get_extra_info.return_value = ("test", 1234)
        writer.drain = AsyncMock()
        writer.wait_closed = AsyncMock(side_effect=ConnectionResetError(64, "peer gone"))
        bridge.run_session = AsyncMock(side_effect=ConnectionResetError(64, "peer gone"))

        await bridge.handle(reader, writer)

        self.assertIsNone(bridge.active)
        writer.close.assert_called_once()
        writer.wait_closed.assert_awaited_once()

        # Another client can claim the slot after the reset.
        writer2 = Mock()
        writer2.get_extra_info.return_value = ("test", 5678)
        writer2.drain = AsyncMock()
        writer2.wait_closed = AsyncMock()
        bridge.run_session = AsyncMock()
        await bridge.handle(reader, writer2)
        bridge.run_session.assert_awaited_once()
        self.assertIsNone(bridge.active)

    async def test_other_errors_are_not_silenced(self):
        writer = Mock()
        writer.wait_closed = AsyncMock(side_effect=RuntimeError("unexpected"))
        with self.assertRaisesRegex(RuntimeError, "unexpected"):
            await Bridge.close_writer(writer)


if __name__ == "__main__":
    unittest.main()
