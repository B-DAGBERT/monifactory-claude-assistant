"""Platform-specific facilities kept outside the upstream application."""

import ctypes
import os

from monifactory_assistant.config import HERE


class SingleInstance:
    def __enter__(self):
        self.handle = None
        if os.name == "nt":
            # ctypes defaults to a 32-bit int result, which truncates HANDLE on Win64.
            self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            self.kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
            self.kernel.CreateMutexW.restype = ctypes.c_void_p
            self.kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            self.kernel.CloseHandle.restype = ctypes.c_int
            self.handle = self.kernel.CreateMutexW(None, False, "AssistantMinecraftCodex")
            error = ctypes.get_last_error()
            if not self.handle:
                raise ctypes.WinError(error)
            return error != 183
        import fcntl

        self.handle = (HERE / "assistant.lock").open("a")
        try:
            fcntl.flock(self.handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            return False

    def __exit__(self, *_):
        if os.name == "nt":
            if self.handle:
                self.kernel.CloseHandle(self.handle)
        else:
            self.handle.close()
