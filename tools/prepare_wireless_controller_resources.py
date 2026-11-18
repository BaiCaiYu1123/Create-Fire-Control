"""Update controller-local translations and borrow display transforms, never third-party textures."""
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LABELS = {
    'item_hint': ('右键激活；退出按键可在配置中修改', 'Right-click to control; configure the exit input in the controller UI', 'ПКМ — управление; вход для выхода меняется в настройках'),
    'item_hint_config': ('蹲下右键：配置键盘、鼠标与红石频道', 'Sneak + right-click: configure keyboard, mouse and frequencies', 'Shift + ПКМ: клавиатура, мышь и частоты'),
    'show_enabled': ('全部 / 已启用', 'All / In Use', 'Все / Активные'),
    'channel_selected': ('当前通道：%s', 'Selected channel: %s', 'Выбранный канал: %s'),
    'title': ('全键盘无线控制器', 'Full Keyboard Wireless Controller', 'Беспроводной клавиатурный контроллер'),
    'select_title': ('选择控制器输入通道', 'Select Controller Input Channel', 'Выбор входного канала'),
    'active': ('遥控已激活 · 按 %s 退出', 'Controller active · %s to exit', 'Управление активно · %s для выхода'),
    'exit_key': ('退出按键：%s', 'Exit input: %s', 'Выход: %s'),
    'exit_capture': ('退出按键：请按键…', 'Exit input: press input…', 'Выход: нажмите…'),
    'paired': ('已绑定无线控制器信号处理器', 'Signal processor paired', 'Обработчик сигналов привязан'),
    'pair_hint': ('手持控制器直接右键信号处理器即可绑定', 'Right-click a signal processor with the controller to pair', 'Нажмите ПКМ по обработчику с контроллером в руке'),
    'busy': ('该信号处理器正在被其他玩家控制', 'This processor is controlled by another player', 'Обработчик уже используется другим игроком'),
    'select_hint': ('按 %s 选择输入通道', 'Press %s to select an input channel', 'Нажмите %s для выбора канала'),
    'search': ('搜索按键 / 鼠标按钮', 'Search keys / mouse buttons', 'Поиск клавиши / кнопки мыши'),
    'all_inputs': ('布局 / 列表', 'Layout / List', 'Схема / Список'),
    'previous': ('◀', '◀', '◀'), 'next': ('▶', '▶', '▶'),
    'record': ('录入按键', 'Capture Input', 'Записать вход'),
    'record_hint': ('请按键或鼠标按钮；Esc 取消', 'Press a key or mouse button; Esc cancels', 'Нажмите клавишу или кнопку мыши; Esc — отмена'),
    'record_short': ('请按键…', 'Press input…', 'Нажмите…'),
    'confirm': ('确认', 'Confirm', 'Принять'), 'cancel': ('取消', 'Cancel', 'Отмена'),
    'select_input': ('请选择输入通道', 'Choose an input channel', 'Выберите входной канал'),
    'pair': ('绑定处理器', 'Pair Processor', 'Привязать'),
    'unpair': ('解除绑定', 'Unpair', 'Отвязать'),
    'clear_all': ('清空红石绑定', 'Clear Frequencies', 'Очистить частоты'),
    'clear_confirm': ('再次点击确认', 'Click Again to Clear', 'Нажмите ещё раз'),
    'save': ('保存', 'Save', 'Сохранить'),
    'hub_ready': ('处理器已连接', 'Processor connected', 'Обработчик подключён'),
    'hub_none': ('未绑定有效处理器', 'No valid processor paired', 'Нет привязанного обработчика'),
    'label': ('用途名称（可留空）', 'Purpose label (optional)', 'Назначение (необязательно)'),
    'enabled': ('已启用', 'Enabled', 'Включено'), 'disabled': ('已关闭', 'Disabled', 'Выключено'),
    'clear_channel': ('清空频道', 'Clear Channel', 'Очистить канал'),
    'save_input': ('保存键位', 'Save Input', 'Сохранить вход'), 'back': ('返回', 'Back', 'Назад'),
    'frequency': ('红石频道', 'Frequency', 'Частота'),
    'wire_used': ('线缆：已使用此输入', 'Wire: input in use', 'Кабель: вход используется'),
    'wire_unused': ('线缆：无有效连接', 'Wire: no live connections', 'Кабель: нет активных соединений'),
    'wireless_used': ('无线红石：已启用', 'Wireless redstone: enabled', 'Беспроводной редстоун: включён'),
    'wireless_disabled': ('无线红石：已关闭', 'Wireless redstone: disabled', 'Беспроводной редстоун: выключен'),
    'wire_short_used': ('线缆：已连接', 'Wire: linked', 'Кабель: есть'),
    'wire_short_unused': ('线缆：未连接', 'Wire: none', 'Кабель: нет'),
    'action_used': ('功能映射：已启用', 'Action mapping: active', 'Привязка действия: активна'),
    'inventory_page': ('背包翻页', 'Inventory Page', 'Страница инв.'),
    'inventory_page_number': ('背包 %s / 2', 'Inventory %s / 2', 'Инвентарь %s / 2'),
}

for index, locale in enumerate(('zh_cn', 'en_us', 'ru_ru')):
    path = ROOT / f'src/main/resources/assets/create_fire_control/lang/{locale}.json'
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    for key in ('unused_hint', 'ghost_hint'):
        data.pop(f'remote.create_fire_control.{key}', None)
    data.update({f'remote.create_fire_control.{key}': value[index] for key, value in LABELS.items()})
    data['item.create_fire_control.wireless_controller'] = LABELS['title'][index]
    data['block.create_fire_control.wireless_signal_processor'] = (
        '无线控制器信号处理器', 'Wireless Controller Signal Processor', 'Обработчик сигналов беспроводного контроллера')[index]
    data['key.create_fire_control.controller_channel'] = (
        '选择无线控制器输入通道', 'Select Wireless Controller Input Channel', 'Выбор входа беспроводного контроллера')[index]
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

advanced = pathlib.Path('D:/Game/PCLII/.minecraft/versions/Create航空学/mods/[机械动力：高级遥控器] create_tweaked_controllers-1.21.1-1.2.7.jar')
if advanced.is_file():
    with zipfile.ZipFile(advanced) as jar:
        model = json.loads(jar.read('assets/create_tweaked_controllers/models/item/tweaked_linked_controller/item.json'))
    path = ROOT / 'src/main/resources/assets/create_fire_control/models/item/wireless_controller.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['display'] = model['display']
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('Prepared zh_cn/en_us/ru_ru controller resources and model transforms.')
