"""Reflection helpers for plugins."""

from java import jclass

_Bridge = jclass("org.telegram.messenger.plugins.PluginBridge")
_Class = jclass("java.lang.Class")

_PRIMITIVES = {
    "int": "java.lang.Integer", "long": "java.lang.Long", "boolean": "java.lang.Boolean",
    "float": "java.lang.Float", "double": "java.lang.Double", "byte": "java.lang.Byte",
    "short": "java.lang.Short", "char": "java.lang.Character", "void": "java.lang.Void",
}


def find_class(name):
    """java.lang.Class for a full class name ("org.telegram.ui.ChatActivity",
    inner classes with $), or None."""
    if name in _PRIMITIVES:
        return jclass(_PRIMITIVES[name]).TYPE
    try:
        return _Class.forName(name, False, _Bridge.getClassLoader())
    except Exception:
        return None


def _as_class(t):
    if isinstance(t, str):
        c = find_class(t)
        if c is None:
            raise ValueError("class not found: " + t)
        return c
    return t


def get_method(cls, name, *param_types):
    """Declared method of cls (a Class or a class name), made accessible."""
    m = _as_class(cls).getDeclaredMethod(name, [_as_class(t) for t in param_types])
    m.setAccessible(True)
    return m


def get_constructor(cls, *param_types):
    c = _as_class(cls).getDeclaredConstructor([_as_class(t) for t in param_types])
    c.setAccessible(True)
    return c


def get_private_field(obj, name):
    return _Bridge.getField(obj, name)


def set_private_field(obj, name, value):
    _Bridge.setField(obj, name, value)


def get_static_private_field(cls, name):
    return _Bridge.getStaticField(_as_class(cls), name)


def set_static_private_field(cls, name, value):
    _Bridge.setStaticField(_as_class(cls), name, value)
