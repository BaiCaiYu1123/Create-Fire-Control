import argparse
import ctypes
import math
import os


FMOD_OK = 0
FMOD_VERSION = 0x00020211
FMOD_OUTPUTTYPE_WAVWRITER_NRT = 5
FMOD_SPEAKERMODE_STEREO = 3
FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE = 1
FMOD_INIT_NORMAL = 0
FMOD_STUDIO_LOAD_BANK_NORMAL = 0
FMOD_STUDIO_STOP_IMMEDIATE = 1


class Vector(ctypes.Structure):
    _fields_ = [("x", ctypes.c_float), ("y", ctypes.c_float), ("z", ctypes.c_float)]


class Attributes3D(ctypes.Structure):
    _fields_ = [
        ("position", Vector),
        ("velocity", Vector),
        ("forward", Vector),
        ("up", Vector),
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
    parser.add_argument("master_bank")
    parser.add_argument("strings_bank")
    parser.add_argument("event_bank")
    parser.add_argument("event")
    parser.add_argument("output")
    parser.add_argument(
        "--profile", choices=("start", "run", "stop"), default="run"
    )
    parser.add_argument("--start-rpm", type=float, default=0.0)
    parser.add_argument("--end-rpm", type=float, default=15.0)
    args = parser.parse_args()

    dll_directory = os.path.dirname(os.path.abspath(args.studio_dll))
    dll_handle = os.add_dll_directory(dll_directory)
    studio = ctypes.CDLL(args.studio_dll)

    system_create = bind(
        studio,
        "FMOD_Studio_System_Create",
        [ctypes.POINTER(ctypes.c_void_p), ctypes.c_uint],
    )
    system_get_core = bind(
        studio,
        "FMOD_Studio_System_GetCoreSystem",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)],
    )
    core_set_output = bind(
        studio,
        "FMOD5_System_SetOutput",
        [ctypes.c_void_p, ctypes.c_int],
    )
    core_set_software_format = bind(
        studio,
        "FMOD5_System_SetSoftwareFormat",
        [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int],
    )
    core_get_dsp_buffer_size = bind(
        studio,
        "FMOD5_System_GetDSPBufferSize",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_int)],
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
    bank_load_sample_data = bind(
        studio,
        "FMOD_Studio_Bank_LoadSampleData",
        [ctypes.c_void_p],
    )
    bank_get_sample_loading_state = bind(
        studio,
        "FMOD_Studio_Bank_GetSampleLoadingState",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)],
    )
    system_get_event = bind(
        studio,
        "FMOD_Studio_System_GetEvent",
        [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)],
    )
    system_flush_sample_loading = bind(
        studio,
        "FMOD_Studio_System_FlushSampleLoading",
        [ctypes.c_void_p],
    )
    system_set_listener_attributes = bind(
        studio,
        "FMOD_Studio_System_SetListenerAttributes",
        [
            ctypes.c_void_p,
            ctypes.c_int,
            ctypes.POINTER(Attributes3D),
            ctypes.POINTER(Vector),
        ],
    )
    event_load_sample_data = bind(
        studio,
        "FMOD_Studio_EventDescription_LoadSampleData",
        [ctypes.c_void_p],
    )
    event_create_instance = bind(
        studio,
        "FMOD_Studio_EventDescription_CreateInstance",
        [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p)],
    )
    instance_set_parameter = bind(
        studio,
        "FMOD_Studio_EventInstance_SetParameterByName",
        [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_float, ctypes.c_bool],
    )
    instance_set_volume = bind(
        studio,
        "FMOD_Studio_EventInstance_SetVolume",
        [ctypes.c_void_p, ctypes.c_float],
    )
    instance_set_3d_attributes = bind(
        studio,
        "FMOD_Studio_EventInstance_Set3DAttributes",
        [ctypes.c_void_p, ctypes.POINTER(Attributes3D)],
    )
    instance_start = bind(
        studio,
        "FMOD_Studio_EventInstance_Start",
        [ctypes.c_void_p],
    )
    instance_stop = bind(
        studio,
        "FMOD_Studio_EventInstance_Stop",
        [ctypes.c_void_p, ctypes.c_int],
    )
    instance_release = bind(
        studio,
        "FMOD_Studio_EventInstance_Release",
        [ctypes.c_void_p],
    )
    system_update = bind(
        studio,
        "FMOD_Studio_System_Update",
        [ctypes.c_void_p],
    )
    system_release = bind(
        studio,
        "FMOD_Studio_System_Release",
        [ctypes.c_void_p],
    )

    sample_rate = 48000
    system = ctypes.c_void_p()
    check(system_create(ctypes.byref(system), FMOD_VERSION), "System_Create")
    try:
        core = ctypes.c_void_p()
        check(system_get_core(system, ctypes.byref(core)), "System_GetCoreSystem")
        check(
            core_set_output(core, FMOD_OUTPUTTYPE_WAVWRITER_NRT),
            "CoreSystem_SetOutput",
        )
        check(
            core_set_software_format(
                core, sample_rate, FMOD_SPEAKERMODE_STEREO, 0
            ),
            "CoreSystem_SetSoftwareFormat",
        )

        output_path = os.path.abspath(args.output)
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        output_buffer = ctypes.create_string_buffer(output_path.encode("utf-8"))
        check(
            system_initialize(
                system,
                64,
                FMOD_STUDIO_INIT_SYNCHRONOUS_UPDATE,
                FMOD_INIT_NORMAL,
                ctypes.cast(output_buffer, ctypes.c_void_p),
            ),
            "System_Initialize",
        )

        banks = []
        for bank_path in (
            args.strings_bank,
            args.master_bank,
            args.event_bank,
        ):
            bank = ctypes.c_void_p()
            check(
                system_load_bank_file(
                    system,
                    os.path.abspath(bank_path).encode("utf-8"),
                    FMOD_STUDIO_LOAD_BANK_NORMAL,
                    ctypes.byref(bank),
                ),
                f"LoadBankFile({bank_path})",
            )
            check(bank_load_sample_data(bank), "Bank_LoadSampleData")
            banks.append(bank)

        check(
            system_flush_sample_loading(system),
            "System_FlushSampleLoading",
        )
        for _ in range(2000):
            check(system_update(system), "System_Update")
            states = []
            for bank in banks:
                state = ctypes.c_int()
                check(
                    bank_get_sample_loading_state(bank, ctypes.byref(state)),
                    "Bank_GetSampleLoadingState",
                )
                states.append(state.value)
            if all(state in (1, 3) for state in states):
                break
            if any(state == 4 for state in states):
                raise RuntimeError(f"Sample data loading failed: {states}")
        else:
            raise RuntimeError(f"Timed out loading sample data: {states}")

        event_description = ctypes.c_void_p()
        check(
            system_get_event(
                system, args.event.encode("utf-8"), ctypes.byref(event_description)
            ),
            "System_GetEvent",
        )
        check(
            event_load_sample_data(event_description),
            "EventDescription_LoadSampleData",
        )
        instance = ctypes.c_void_p()
        check(
            event_create_instance(event_description, ctypes.byref(instance)),
            "EventDescription_CreateInstance",
        )
        listener = Attributes3D(
            Vector(0, 0, 0),
            Vector(0, 0, 0),
            Vector(0, 0, 1),
            Vector(0, 1, 0),
        )
        source = Attributes3D(
            Vector(1, 0, 0),
            Vector(0, 0, 0),
            Vector(0, 0, 1),
            Vector(0, 1, 0),
        )
        check(
            system_set_listener_attributes(
                system, 0, ctypes.byref(listener), None
            ),
            "System_SetListenerAttributes",
        )
        check(
            instance_set_3d_attributes(instance, ctypes.byref(source)),
            "EventInstance_Set3DAttributes",
        )

        block_length = ctypes.c_uint()
        block_count = ctypes.c_int()
        check(
            core_get_dsp_buffer_size(
                core, ctypes.byref(block_length), ctypes.byref(block_count)
            ),
            "CoreSystem_GetDSPBufferSize",
        )
        updates_per_second = sample_rate / block_length.value

        def set_parameter(name: str, value: float) -> None:
            result = instance_set_parameter(
                instance, name.encode("utf-8"), value, True
            )
            if result not in (FMOD_OK, 19):
                check(result, f"SetParameterByName({name})")

        if args.profile == "run":
            check(instance_set_volume(instance, 0.5), "EventInstance_SetVolume")
            set_parameter("rpm", 15)
            set_parameter("load", 0)
            set_parameter("loadswitch", 0)
        elif args.profile == "start":
            set_parameter("rpm", args.start_rpm)
            set_parameter("climb", 0)
        check(instance_start(instance), "EventInstance_Start")

        if args.profile == "start":
            duration = 8.0
            updates = math.ceil(duration * updates_per_second)
            for update in range(updates):
                progress = update / max(1, updates - 1)
                set_parameter(
                    "rpm",
                    args.start_rpm
                    + (args.end_rpm - args.start_rpm) * progress,
                )
                check(system_update(system), "System_Update")
        elif args.profile == "stop":
            duration = 7.0
            for _ in range(math.ceil(duration * updates_per_second)):
                check(system_update(system), "System_Update")
        else:
            phases = [
                (4.0, 15.0, 15.0, 0.0, 0.0),
                (7.0, 15.0, 100.0, 1.0, 1.0),
                (4.0, 100.0, 100.0, 1.0, 1.0),
                (6.0, 100.0, 55.0, 0.2, 0.2),
                (4.0, 55.0, 15.0, 0.0, 0.0),
                (3.0, 15.0, 15.0, 0.0, 0.0),
            ]
            for duration, start_rpm, end_rpm, start_load, end_load in phases:
                updates = math.ceil(duration * updates_per_second)
                for update in range(updates):
                    progress = update / max(1, updates - 1)
                    set_parameter(
                        "rpm", start_rpm + (end_rpm - start_rpm) * progress
                    )
                    set_parameter(
                        "load", start_load + (end_load - start_load) * progress
                    )
                    set_parameter(
                        "loadswitch",
                        start_load + (end_load - start_load) * progress,
                    )
                    check(system_update(system), "System_Update")

        stop_result = instance_stop(instance, FMOD_STUDIO_STOP_IMMEDIATE)
        if stop_result not in (FMOD_OK, 30):
            check(stop_result, "EventInstance_Stop")
        for _ in range(math.ceil(0.5 * updates_per_second)):
            check(system_update(system), "System_Update")
        release_result = instance_release(instance)
        if release_result not in (FMOD_OK, 30):
            check(release_result, "EventInstance_Release")
    finally:
        check(system_release(system), "System_Release")
        dll_handle.close()

    print(args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
