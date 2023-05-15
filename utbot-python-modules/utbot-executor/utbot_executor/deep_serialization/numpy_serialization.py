def is_numpy_ndarray(obj: object) -> bool:
    try:
        if hasattr(type(obj), '__module__') and hasattr(type(obj), '__qualname__'):
            module_check = type(obj).__module__ == 'numpy'
            name_check = type(obj).__qualname__ == 'ndarray'
            return module_check and name_check
        import numpy
        return isinstance(obj, numpy.ndarray)
    except Exception:
        return False
