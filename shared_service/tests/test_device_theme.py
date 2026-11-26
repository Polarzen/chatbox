"""Exercise appearance revisions and pairing authorization through the phone gateway."""

import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
import secrets
import unittest

from shared_service import device_runtime, service
from shared_service.device_gateway import Gateway

if __package__:
    from . import test_device_service as devices
else:
    import test_device_service as devices

JPEG = ("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIs"
        "IxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAAB//EABQQAQAAAAAA"
        "AAAAAAAAAAAAAAD/2gAIAQEAAD8AcH//2Q==")
THEME = dict(dark=False, accent="#AD7DFF", wallpaper=JPEG, blur=23)


class DeviceThemeTests(unittest.TestCase):
    def setUp(self):
        self.d = d = devices.DeviceServiceTests()
        self.addCleanup(d.doCleanups)
        d.setUp()
        phone, self.phone_id = d.guest()
        stack = ExitStack()
        self.addCleanup(stack.close)
        gate = stack.enter_context(Gateway(0, d.host.port, device_runtime.PUBLIC_ROUTES, device_runtime.PROBE,
                                           secrets.token_hex(32), service.MAX_BODY))
        stack.enter_context(device_runtime.serving(gate))
        self.phone = devices.Client(gate.server_port, phone.token)
        self.public = devices.Client(gate.server_port)

    def save(self, client, revision, theme):
        return self.d.post(client, "theme", expectedRevision=revision, theme=theme)

    def test_paired_roundtrip_conflicts_conditional_reads_and_reset(self):
        d = self.d
        self.assertEqual(d.get(self.phone, "theme"), (200, dict(revision=0, theme=None)))
        self.assertEqual(self.save(self.phone, 0, THEME), (200, dict(revision=1)))
        self.assertEqual(d.get(d.pc, "theme", since=0), (200, dict(revision=1, theme=THEME)))
        self.assertEqual(d.get(d.pc, "theme", since=1), (200, dict(revision=1)))
        # Losing the response must not duplicate a successful revision.
        self.assertEqual(self.save(self.phone, 0, THEME), (200, dict(revision=1)))
        with ThreadPoolExecutor(2) as pool:
            futures = [pool.submit(self.save, client, 1, dict(THEME, blur=blur))
                       for client, blur in ((d.pc, 24), (self.phone, 25))]
            self.assertEqual(sorted(f.result()[0] for f in futures), [200, 409])
        current = d.ok(d.get(d.pc, "theme"))
        d.host.restart()
        self.assertEqual(d.ok(d.get(self.phone, "theme")), current)
        self.assertEqual(self.save(d.pc, 2, None), (200, dict(revision=3)))
        self.assertEqual(d.get(self.phone, "theme", since=2), (200, dict(revision=3, theme=None)))

    def test_rejected_credentials_and_revocation(self):
        d = self.d
        self.assertEqual(d.get(self.public, "theme")[0], 401)
        self.assertEqual(self.save(self.public, 0, THEME)[0], 401)
        d.ok(self.save(self.phone, 0, THEME))
        d.ok(d.post(d.pc, "revoke", device=self.phone_id))
        self.assertEqual(d.get(self.phone, "theme")[0], 401)
        self.assertEqual(self.save(self.phone, 1, None)[0], 401)
        self.assertEqual(d.ok(d.get(d.pc, "theme"))["theme"], THEME)

    def test_limits_validation_and_atomicity(self):
        d = self.d
        invalid_dimensions = bytearray(base64.b64decode(JPEG))
        frame = invalid_dimensions.index(b"\xff\xc0")
        invalid_dimensions[frame + 5:frame + 7] = (1601).to_bytes(2, "big")
        large = base64.b64encode(bytes(512 * 1024 + 1)).decode()
        for patch, status in [
                (dict(dark=1), 400), (dict(blur=True), 400), (dict(blur=101), 400),
                (dict(accent="red"), 400), (dict(wallpaper=large), 413), (dict(wallpaper="***"), 400),
                (dict(wallpaper=base64.b64encode(invalid_dimensions).decode()), 400),
                (dict(wallpaper=base64.b64encode(b"bad").decode()), 400), (dict(extra=True), 400)]:
            with self.subTest(fields=list(patch)):
                self.assertEqual(self.save(self.phone, 0, THEME | patch)[0], status)
        for revision in (-1, True, 2**64):
            self.assertEqual(self.save(self.phone, revision, THEME)[0], 400)
        for since in ("", "-1", "+1", "n", str(2**64)):
            self.assertEqual(d.get(self.phone, "theme", since=since)[0], 400)
        self.assertEqual(d.get(d.pc, "theme"), (200, dict(revision=0, theme=None)))
