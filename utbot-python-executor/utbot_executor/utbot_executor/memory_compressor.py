import typing

from utbot_executor.deep_serialization.memory_objects import MemoryDump
from utbot_executor.deep_serialization.utils import PythonId


def comparator(left: object, right: object) -> bool:
    if type(left).__module__ == 'numpy':
        if type(left).__qualname__ == 'ndarray':
            return bool((left == right).all())
        else:
            return (left == right).__bool__()
    else:
        return left == right


def compress_memory(
        ids: typing.List[PythonId],
        state_before: MemoryDump,
        state_after: MemoryDump
) -> typing.List[PythonId]:
    diff_ids: typing.List[PythonId] = []
    for id_ in ids:
        if id_ in state_before.objects and id_ in state_after.objects:
            if not comparator(state_before.objects[id_].obj, state_after.objects[id_].obj):
                diff_ids.append(id_)
    return diff_ids
