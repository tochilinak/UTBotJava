from __future__ import annotations

from itertools import zip_longest
import pickle
from typing import Any, Callable, Dict, List, Optional, Set, Type, Final, Iterable

from utbot_executor.utbot_executor.deep_serialization.numpy_serialization import is_numpy_ndarray
from utbot_executor.utbot_executor.deep_serialization.utils import PythonId, get_kind, has_reduce, check_comparability, get_repr, \
    has_repr, TypeInfo, get_constructor_kind

PICKLE_PROTO: Final = 4


class MemoryObject:
    strategy: str
    typeinfo: TypeInfo
    comparable: bool
    is_draft: bool
    deserialized_obj: object
    obj: object

    def __init__(self, obj: object) -> None:
        self.is_draft = True
        self.typeinfo = get_kind(obj)
        self.obj = obj

    def _initialize(self, deserialized_obj: object = None, comparable: bool = True) -> None:
        self.deserialized_obj = deserialized_obj
        self.comparable = comparable
        self.is_draft = False

    def initialize(self) -> None:
        self._initialize()

    def id_value(self) -> str:
        return str(id(self.obj))

    def __repr__(self) -> str:
        if hasattr(self, 'obj'):
            return str(self.obj)
        return str(self.typeinfo)

    def __str__(self) -> str:
        return str(self.obj)

    @property
    def qualname(self) -> str:
        return self.typeinfo.qualname


class ReprMemoryObject(MemoryObject):
    strategy: str = 'repr'
    value: str

    def __init__(self, repr_object: object) -> None:
        super().__init__(repr_object)
        self.value = get_repr(repr_object)

    def initialize(self) -> None:
        try:
            deserialized_obj = pickle.loads(pickle.dumps(self.obj))
            comparable = check_comparability(self.obj, deserialized_obj)
        except Exception:
            deserialized_obj = self.obj
            comparable = False

        super()._initialize(deserialized_obj, comparable)


class ListMemoryObject(MemoryObject):
    strategy: str = 'list'
    items: List[PythonId] = []

    def __init__(self, list_object: object) -> None:
        self.items: List[PythonId] = []
        super().__init__(list_object)

    def initialize(self) -> None:
        serializer = PythonSerializer()

        for elem in self.obj:
            elem_id = serializer.write_object_to_memory(elem)
            self.items.append(elem_id)

        deserialized_obj = [serializer[elem] for elem in self.items]
        if self.typeinfo.fullname == 'builtins.tuple':
            deserialized_obj = tuple(deserialized_obj)
        elif self.typeinfo.fullname == 'builtins.set':
            deserialized_obj = set(deserialized_obj)

        comparable = all(serializer.get_by_id(elem).comparable for elem in self.items)

        super()._initialize(deserialized_obj, comparable)

    def __repr__(self) -> str:
        if hasattr(self, 'obj'):
            return str(self.obj)
        return f'{self.typeinfo.kind}{self.items}'


class DictMemoryObject(MemoryObject):
    strategy: str = 'dict'
    items: Dict[PythonId, PythonId] = {}

    def __init__(self, dict_object: object) -> None:
        self.items: Dict[PythonId, PythonId] = {}
        super().__init__(dict_object)

    def initialize(self) -> None:
        self.obj: Dict
        serializer = PythonSerializer()

        for key, value in self.obj.items():
            key_id = serializer.write_object_to_memory(key)
            value_id = serializer.write_object_to_memory(value)
            self.items[key_id] = value_id

        deserialized_obj = {
            serializer[key_id]: serializer[value_id]
            for key_id, value_id in self.items.items()
        }
        equals_len = len(self.obj) == len(deserialized_obj)
        comparable = equals_len and\
                all(serializer.get_by_id(value_id).comparable for elem in self.items)

        super()._initialize(deserialized_obj, comparable)

    def __repr__(self) -> str:
        if hasattr(self, 'obj'):
            return str(self.obj)
        return f'{self.typeinfo.kind}{self.items}'


class ReduceMemoryObject(MemoryObject):
    strategy: str = 'reduce'
    constructor: TypeInfo
    args: PythonId
    state: PythonId
    listitems: PythonId
    dictitems: PythonId
    comment: Optional[str] = None

    reduce_value: List[Any] = []

    def __init__(self, reduce_object: object) -> None:
        super().__init__(reduce_object)
        serializer = PythonSerializer()

        py_object_reduce = reduce_object.__reduce__()
        self.reduce_value = [
            default if obj is None else obj
            for obj, default in zip_longest(
                py_object_reduce,
                [None, [], {}, [], {}],
                fillvalue=None
                )
        ]

        constructor_kind = get_constructor_kind(self.reduce_value[0])

        is_reconstructor = constructor_kind.qualname == 'copyreg._reconstructor'
        is_user_type = len(self.reduce_value[1]) == 3 and self.reduce_value[1][1] is object and self.reduce_value[1][2] is None
        is_ndarray = is_numpy_ndarray(self.obj)

        callable_constructor: Callable
        if is_ndarray:
            callable_constructor = lambda _: self.obj
            self.constructor = TypeInfo('numpy', 'array')
            self.args = serializer.write_object_to_memory([self.obj.data.tolist()])
            self.reduce_value[2] = {}
            self.reduce_value[3] = []
            self.reduce_value[4] = {}
        elif is_reconstructor and is_user_type:
            callable_constructor = self.reduce_value[1][0].__new__
            self.constructor = TypeInfo('builtins', 'object.__new__')
            self.args = serializer.write_object_to_memory([self.reduce_value[1][0]])
        else:
            callable_constructor = self.reduce_value[0]
            self.constructor = constructor_kind
            self.args = serializer.write_object_to_memory(self.reduce_value[1])

        args = serializer[self.args]
        if isinstance(args, Iterable):
            self.deserialized_obj = callable_constructor(*args)

    def initialize(self) -> None:
        serializer = PythonSerializer()

        self.comparable = True  # for recursive objects
        self.state = serializer.write_object_to_memory(self.reduce_value[2])
        self.listitems = serializer.write_object_to_memory(list(self.reduce_value[3]))
        self.dictitems = serializer.write_object_to_memory(dict(self.reduce_value[4]))

        deserialized_obj = self.deserialized_obj
        state = serializer[self.state]
        if isinstance(state, dict):
            for key, value in state.items():
                setattr(deserialized_obj, key, value)
        elif hasattr(deserialized_obj, '__setstate__'):
            deserialized_obj.__setstate__(state)

        items = serializer[self.listitems]
        if isinstance(items, Iterable):
            for item in items:
                deserialized_obj.append(item)

        dictitems = serializer[self.dictitems]
        if isinstance(dictitems, Dict):
            for key, value in dictitems.items():
                deserialized_obj[key] = value

        comparable = self.obj == deserialized_obj
        if hasattr(type(comparable), '__module__') and type(comparable).__module__ == 'numpy':
            if is_numpy_ndarray(self.obj):
                comparable = True
            else:
                self.comment = str(self.obj)
                comparable = comparable.__bool__()

        super()._initialize(deserialized_obj, comparable)


class MemoryObjectProvider(object):
    @staticmethod
    def get_serializer(obj: object) -> Optional[Type[MemoryObject]]:
        pass


class ListMemoryObjectProvider(MemoryObjectProvider):
    @staticmethod
    def get_serializer(obj: object) -> Optional[Type[MemoryObject]]:
        if isinstance(obj, (list, set, tuple, frozenset)):  # any(type(obj) == t for t in (list, set, tuple)):
            return ListMemoryObject
        return None


class DictMemoryObjectProvider(MemoryObjectProvider):
    @staticmethod
    def get_serializer(obj: object) -> Optional[Type[MemoryObject]]:
        if isinstance(obj, dict):  # type(obj) == dict:
            return DictMemoryObject
        return None


class ReduceMemoryObjectProvider(MemoryObjectProvider):
    @staticmethod
    def get_serializer(obj: object) -> Optional[Type[MemoryObject]]:
        if has_reduce(obj):
            return ReduceMemoryObject
        return None


class ReprMemoryObjectProvider(MemoryObjectProvider):
    @staticmethod
    def get_serializer(obj: object) -> Optional[Type[MemoryObject]]:
        if has_repr(obj):
            return ReprMemoryObject
        return None


class MemoryDump:
    objects: Dict[PythonId, MemoryObject]

    def __init__(self, objects: Optional[Dict[PythonId, MemoryObject]] = None):
        if objects is None:
            objects = {}
        self.objects = objects


class PythonSerializer:
    instance: PythonSerializer
    memory: MemoryDump
    created: bool = False

    visited: Set[PythonId] = set()

    providers: List[MemoryObjectProvider] = [
            ListMemoryObjectProvider,
            DictMemoryObjectProvider,
            ReduceMemoryObjectProvider,
            ReprMemoryObjectProvider,
            ]

    def __new__(cls):
        if not cls.created:
            cls.instance = super(PythonSerializer, cls).__new__(cls)
            cls.memory = MemoryDump()
            cls.created = True
        return cls.instance

    def clear(self):
        self.memory = MemoryDump()

    def get_by_id(self, id_: PythonId) -> MemoryObject:
        return self.memory.objects[id_]

    def __getitem__(self, id_: PythonId) -> object:
        return self.get_by_id(id_).deserialized_obj

    def clear_visited(self):
        self.visited.clear()

    def write_object_to_memory(self, py_object: object) -> PythonId:
        """Save serialized py_object to memory and return id."""

        id_ = PythonId(str(id(py_object)))

        if id_ in self.visited:
            return id_

        for provider in self.providers:
            serializer = provider.get_serializer(py_object)
            if serializer is not None:
                self.visited.add(id_)
                mem_obj = serializer(py_object)
                self.memory.objects[id_] = mem_obj
                mem_obj.initialize()
                return id_

        raise ValueError(f'Can not find provider for object {py_object}.')
