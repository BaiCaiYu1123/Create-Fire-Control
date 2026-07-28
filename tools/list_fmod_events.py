import argparse
import ctypes
import os


FMOD_OK = 0
FMOD_VERSION = 0x00020211
FMOD_STUDIO_INIT_NORMAL = 0
FMOD_INIT_NORMAL = 0
FMOD_STUDIO_LOAD_BANK_NORMAL = 0


class ParameterDescription(ctypes.Structure):
    _fields_ = [
        ("name", ctypes.c_void_p),
        ("id_data1", ctypes.c_uint),
        ("id_data2", ctypes.c_uint),
        ("minimum", ctypes.c_float),
        ("maximum", ctypes.c_float),
        ("default_value", ctypes.c_float),
        ("parameter_type", ctypes.c_int),
        ("flags", ctypes.c_uint),
        ("guid", ctypes.c_ubyte * 16),
    ]


def check(result: int, operation: str) -> None:
    if result != FMOD_OK:
        raise RuntimeError(f"{operation} failed with FMOD error {result}")


def bind(library: ctypes.CDLL, name: str, argument_types, result_type=ctypes.c_int):
    function = getattr(library, name)
    function.argtypes = argument_types
    function.restype = result_type
    return function


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("studio_dll")
    parser.add_argument("banks", nargs="+")
    parser.add_argument("--filter", default="")
    parser.add_argument("--parameters", action="store_true")
    args = parser.parse_args()

    os.add_dll_directory(os.path.dirname(os.path.abspath(args.studio_dll)))
    studio = ctypes.CDLL(args.studio_dll)

    system_create = bind(
        studio,
        "FMOD_Studio_System_Create",
        [ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint],
    )
    system_initialize = bind(
        studio,
        "FMOD_Studio_System_Initialize",
        [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint, ctypes.c_uint, ctypes.c_void_p],
    )
    system_load_bank_file = bind(
        studio,
        "FMOD_Studio_System_LoadBankFile",
        [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint, ctypes.POINTER(ctypes.c_void_p)],
    )
    bank_get_event_count = bind(
        studio,
        "FMOD_Studio_Bank_GetEventCount",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)],
    )
    bank_get_event_list = bind(
        studio,
        "FMOD_Studio_Bank_GetEventList",
        [
            ctypes.c_void_p,
            ctypes.POINTER(ctypes.c_void_p),
            ctypes.c_int,
            ctypes.POINTER(ctypes.c_int),
        ],
    )
    event_get_path = bind(
        studio,
        "FMOD_Studio_EventDescription_GetPath",
        [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.POINTER(ctypes.c_int)],
    )
    event_get_parameter_count = bind(
        studio,
        "FMOD_Studio_EventDescription_GetParameterDescriptionCount",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)],
    )
    event_get_parameter_by_index = bind(
        studio,
        "FMOD_Studio_EventDescription_GetParameterDescriptionByIndex",
        [ctypes.c_void_p, ctypes.c_int, ctypes.POINTER(ParameterDescription)],
    )
    system_release = bind(
        studio,
        "FMOD_Studio_System_Release",
        [ctypes.c_void_p],
    )

    system = ctypes.c_void_p()
    check(system_create(ctypes.byref(system), FMOD_VERSION), "System_Create")
    try:
        check(
            system_initialize(
                system, 64, FMOD_STUDIO_INIT_NORMAL, FMOD_INIT_NORMAL, None
            ),
            "System_Initialize",
        )
        for bank_path in args.banks:
            bank = ctypes.c_void_p()
            encoded_path = os.path.abspath(bank_path).encode("utf-8")
            check(
                system_load_bank_file(
                    system,
                    encoded_path,
                    FMOD_STUDIO_LOAD_BANK_NORMAL,
                    ctypes.byref(bank),
                ),
                f"LoadBankFile({bank_path})",
            )

            count = ctypes.c_int()
            check(bank_get_event_count(bank, ctypes.byref(count)), "Bank_GetEventCount")
            events = (ctypes.c_void_p * count.value)()
            returned = ctypes.c_int()
            check(
                bank_get_event_list(
                    bank, events, count.value, ctypes.byref(returned)
                ),
                "Bank_GetEventList",
            )
            print(f"# {bank_path}: {returned.value} events")
            for index in range(returned.value):
                required = ctypes.c_int()
                event_get_path(events[index], None, 0, ctypes.byref(required))
                buffer = ctypes.create_string_buffer(required.value)
                check(
                    event_get_path(
                        events[index], buffer, len(buffer), ctypes.byref(required)
                    ),
                    "EventDescription_GetPath",
                )
                path = buffer.value.decode("utf-8", errors="replace")
                if args.filter.lower() not in path.lower():
                    continue
                print(path)
                if args.parameters:
                    parameter_count = ctypes.c_int()
                    check(
                        event_get_parameter_count(
                            events[index], ctypes.byref(parameter_count)
                        ),
                        "EventDescription_GetParameterDescriptionCount",
                    )
                    for parameter_index in range(parameter_count.value):
                        parameter = ParameterDescription()
                        check(
                            event_get_parameter_by_index(
                                events[index],
                                parameter_index,
                                ctypes.byref(parameter),
                            ),
                            "EventDescription_GetParameterDescriptionByIndex",
                        )
                        name = (
                            ctypes.string_at(parameter.name).decode(
                                "utf-8", errors="replace"
                            )
                            if parameter.name
                            else ""
                        )
                        print(
                            "  "
                            f"{name}: {parameter.minimum:g}..{parameter.maximum:g}, "
                            f"default={parameter.default_value:g}, "
                            f"type={parameter.parameter_type}, flags={parameter.flags}"
                        )
    finally:
        check(system_release(system), "System_Release")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
