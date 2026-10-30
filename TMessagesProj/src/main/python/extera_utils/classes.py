"""exteraGram's java_subclass helpers.

MyTelegram's Python (Chaquopy) can implement Java interfaces but not subclass Java
classes at runtime. java_subclass(SomeInterface) works; for a class, the Python class
is returned as is and new_instance() creates the plain Java base object, so code that
only needs an object of that type keeps working while overridden methods are not called.
"""

from java import dynamic_proxy


def joverride(*args, **kwargs):
    def deco(fn):
        fn._joverride = True
        return fn
    if len(args) == 1 and callable(args[0]) and not kwargs:
        return deco(args[0])
    return deco


class Base:
    java = None

    @classmethod
    def new_instance(cls, *args):
        self = cls.__new__(cls)
        self.java = cls._make_java(self, *args)
        if hasattr(self, "on_post_init"):
            try:
                self.on_post_init(*args)
            except Exception:
                pass
        return self

    @classmethod
    def from_java(cls, obj):
        py = getattr(obj, "_py_owner", None)
        if py is not None:
            return py
        self = cls.__new__(cls)
        self.java = obj
        return self

    @classmethod
    def _make_java(cls, self, *args):
        base = cls._java_base
        try:
            return base(*args)
        except Exception:
            return None

    def __getattr__(self, name):
        java = self.__dict__.get("java")
        if java is None:
            raise AttributeError(name)
        return getattr(java, name)


def java_subclass(java_cls, *more):
    def deco(cls):
        cls._java_base = java_cls
        try:
            is_interface = java_cls.getClass().isInterface() if hasattr(java_cls, "getClass") else False
        except Exception:
            is_interface = False
        if is_interface:
            methods = {k: v for k, v in cls.__dict__.items() if callable(v) and getattr(v, "_joverride", False)}

            def make(self, *args):
                owner = self
                attrs = {k: (lambda f: lambda proxy, *a: f(owner, *a))(v) for k, v in methods.items()}
                proxy_cls = type(cls.__name__ + "_Proxy", (dynamic_proxy(java_cls),), attrs)
                return proxy_cls()
            cls._make_java = classmethod(lambda c, self, *args: make(self, *args))
        return cls
    return deco
