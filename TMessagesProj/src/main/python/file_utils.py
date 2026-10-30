"""File helpers for plugins (exteraGram's file_utils)."""

import os
import shutil

from java import jclass

_PC = jclass("org.telegram.messenger.plugins.PluginsController")
_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")


def get_plugins_dir():
    return str(_PC.getPluginsDir())


def get_files_dir():
    return str(_ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath())


def get_cache_dir():
    return str(_ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())


def get_images_dir():
    return _media_dir(0)


def get_audios_dir():
    return _media_dir(1)


def get_videos_dir():
    return _media_dir(2)


def get_documents_dir():
    return _media_dir(3)


def _media_dir(kind):
    try:
        d = jclass("org.telegram.messenger.FileLoader").getDirectory(kind)
        return str(d.getAbsolutePath()) if d is not None else get_cache_dir()
    except Exception:
        return get_cache_dir()


def ensure_dir_exists(path):
    os.makedirs(path, exist_ok=True)
    return path


def list_dir(path, recursive=False, include_files=True, include_dirs=False, extensions=None):
    out = []
    if recursive:
        walker = os.walk(path)
    else:
        try:
            names = os.listdir(path)
        except OSError:
            return out
        walker = [(path, [n for n in names if os.path.isdir(os.path.join(path, n))],
                   [n for n in names if os.path.isfile(os.path.join(path, n))])]
    for root, dirs, files in walker:
        if include_dirs:
            out.extend(os.path.join(root, d) for d in dirs)
        if include_files:
            for f in files:
                if extensions and not any(f.lower().endswith(e.lower()) for e in extensions):
                    continue
                out.append(os.path.join(root, f))
    return out


def read_file(path, binary=False):
    try:
        with open(path, "rb" if binary else "r", **({} if binary else {"encoding": "utf-8"})) as f:
            return f.read()
    except OSError:
        return None


def write_file(path, content, binary=None):
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    if binary is None:
        binary = isinstance(content, (bytes, bytearray))
    with open(path, "wb" if binary else "w", **({} if binary else {"encoding": "utf-8"})) as f:
        f.write(content)
    return True


def delete_file(path):
    try:
        if os.path.isdir(path):
            shutil.rmtree(path)
        else:
            os.remove(path)
        return True
    except OSError:
        return False
