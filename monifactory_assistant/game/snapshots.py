"""A short-lived, thread-local snapshot shared only by one render cycle."""

from contextlib import contextmanager
from contextvars import ContextVar

_FRAME = ContextVar("assistant_render_frame", default=None)


@contextmanager
def render_frame(app):
    token = _FRAME.set((app, {}))
    try:
        yield
    finally:
        _FRAME.reset(token)


def frame_value(app, key, compute):
    frame = _FRAME.get()
    if frame is None or (frame[0] is not app and getattr(frame[0], "storage", None) is not app):
        return compute()
    cache = frame[1]
    identity = (id(app), key)
    if identity not in cache:
        cache[identity] = compute()
    return cache[identity]


def file_stamp(path):
    """Identity of a file revision, including replacements with the same size and date."""
    try:
        stat = path.stat()
    except FileNotFoundError:
        return None
    return stat.st_ino, stat.st_mtime_ns, stat.st_size
