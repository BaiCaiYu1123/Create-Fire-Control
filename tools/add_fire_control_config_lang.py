"""Merge the configuration UI translations without changing existing translations."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LABELS = {
    'scope': '瞄具', 'scope.zoom': '瞄准镜倍率', 'scope.podZoom': '光电吊舱倍率',
    'virtualMissiles': '弹道导弹显示', 'virtualMissiles.renderDistance': '导弹显示距离（格）',
    'virtualMissiles.nearLodDistance': '近距离细节范围（格）',
    'virtualMissiles.midLodDistance': '中距离细节范围（格）',
    'virtualMissiles.flareRenderDistance': '干扰弹显示距离（格）',
    'virtualMissiles.unknownTerrainFadeAltitude': '未知地形淡出高度（格）',
    'virtualMissiles.trailSamples': '尾迹采样数量',
    'virtualMissiles.fadeWhenLowAndTerrainUnknown': '未知地形低空导弹淡出',
    'virtualBallisticMissile': '弹道导弹进阶参数',
    'initialSpeed': '初始速度（格/秒）', 'boostAcceleration': '助推加速度（格/秒²）',
    'cruiseSpeed': '中段速度（格/秒）', 'terminalAcceleration': '末段加速度（格/秒²）',
    'terminalMaxSpeed': '末段最大速度（格/秒）',
    'boostTurnRateDegreesPerSecond': '助推转向速度（度/秒）',
    'midcourseTurnRateDegreesPerSecond': '中段转向速度（度/秒）',
    'terminalTurnRateDegreesPerSecond': '末段转向速度（度/秒）',
    'minApogee': '最低弹道顶点（格）', 'maxApogee': '最高弹道顶点（格）',
    'apogeeDistanceFactor': '弹道顶点距离系数', 'terminalStartDistance': '末段开始距离（格）',
    'impactChunkPrepareSeconds': '落点区块提前准备时间（秒）',
    'impactChunkRadius': '落点区块加载半径', 'missileSyncIntervalTicks': '导弹同步间隔（刻）',
    'missileMaxRenderDistance': '导弹最大显示距离（格）', 'flareEnabled': '启用干扰弹',
    'flareStartPhase': '干扰弹释放阶段', 'flareBurstIntervalTicks': '干扰弹释放间隔（刻）',
    'flareCountPerBurst': '每次释放干扰弹数量', 'flareLifetimeTicks': '干扰弹寿命（刻）',
    'flareHeat': '干扰弹热强度', 'flareDispersionBlocksPerSecond': '干扰弹散布速度（格/秒）',
    'flareBackwardImpulseBlocksPerSecond': '干扰弹后抛速度（格/秒）',
    'flareRenderDistance': '干扰弹显示距离（格）',
    'reference': '参考音效', 'spatial': '空间音效', 'mix': '混音', 'smoothing': '声音过渡',
    'suMapping': '应力音效映射', 'takes': '瞬态音效', 'debug': '调试',
    'trackTrimDb': '各音轨增益微调（分贝）', 'loadAutomation': '负载音量曲线',
    'layer00': '怠速音轨', 'layer06': '负载音轨',
    'mode': '音效模式', 'layers': '音效层次',
    'enableRpmLoudnessCompensation': '转速响度补偿（兼容项）',
    'referenceMasterDb': '参考音效总增益（分贝）', 'referenceRevvDb': '参考加速音增益（分贝）',
    'referenceStartStopDb': '参考启停音增益（分贝）', 'climbFullAcceleration': '满加速负载阈值',
    'climbAttackSeconds': '负载音淡入时间（秒）', 'climbReleaseSeconds': '负载音淡出时间（秒）',
    'loadswitchFullDemand': '满负载切换阈值', 'revvRandomSemitones': '加速音随机音高（半音）',
    'distanceScale': '距离缩放', 'cullDistance': '声音截止距离（格）',
    'highFrequencyLossDb': '高频衰减（分贝）', 'masterDb': '总增益（分贝）',
    'loopsDb': '循环音增益（分贝）', 'revvDb': '加速音增益（分贝）',
    'releaseDb': '减速音增益（分贝）', 'blipsDb': '短促加速音增益（分贝）',
    'startStopDb': '启停音增益（分贝）', 'transientsDb': '瞬态音增益（分贝）',
    'peakCeiling': '峰值音量上限', 'rpmSeconds': '转速平滑时间（秒）',
    'rpmSeekPerSecond': '音效转速每秒最大变化', 'pitchSeconds': '音高平滑时间（秒）',
    'suLoadSeconds': '应力负载平滑时间（秒）', 'gainSeconds': '音量平滑时间（秒）',
    'maxDbPerSecond': '每秒最大音量变化（分贝）', 'utilizationExponent': '负载映射指数',
    'unloadedGroupDb': '空载音轨增益（分贝）', 'loadedGroupDb': '负载音轨增益（分贝）',
    'enableRevv': '启用加速音', 'enableRelease': '启用减速音', 'enableBlips': '启用短促加速音',
    'triggerRpmPerSecond': '瞬态音触发转速变化', 'cooldownSeconds': '触发冷却时间（秒）',
    'blipCooldownSeconds': '短促加速音冷却时间（秒）', 'maxConcurrent': '最大同时播放数量',
    'enabled': '启用调试', 'auditionMode': '音效试听模式',
    'revvReleaseCooldownSeconds': '加减速音冷却时间（秒）', 'startLoad': '起始负载',
    'startDb': '起始增益（分贝）', 'endLoad': '结束负载', 'endDb': '结束增益（分贝）',
    'curveShape': '曲线形状',
}

def merge():
    translations = {}
    for fragment in ('config-ui-translations', 'missile-config-translations', 'global-config-translations'):
        file = ROOT / 'tools/config_lang' / (fragment + '.json')
        extra = json.loads(file.read_text(encoding='utf-8-sig'))
        for language, values in extra.items():
            translations.setdefault(language, {}).update(values)
    translations['zh_cn'] = {'config.create_fire_control.' + key: text for key, text in LABELS.items()} | translations['zh_cn']
    # Each row is [English, Simplified Chinese, Russian]. Keep user-visible names separate from TOML keys.
    catalog = json.loads((ROOT / 'tools/config_lang/localized-labels.json').read_text(encoding='utf-8'))
    for prefix, rows in catalog.items():
        for suffix, texts in rows.items():
            for language, value in zip(('en_us', 'zh_cn', 'ru_ru'), texts, strict=True):
                translations.setdefault(language, {})[prefix + suffix] = value
    for language, values in translations.items():
        for key, value in list(values.items()):
            if key.startswith('create_fire_control.config.missile.'):
                model = key.removeprefix('create_fire_control.config.missile.')
                values['config.create_fire_control.missiles.' + model] = value
            if key.startswith('create_fire_control.configuration.'):
                values[key.replace('create_fire_control.configuration.', 'config.create_fire_control.', 1)] = value
        if language not in ('en_us', 'zh_cn', 'ru_ru'):
            continue
        column = ('en_us', 'zh_cn', 'ru_ru').index(language)
        families = {
            'blips': ['Throttle blip', '短促加速音', 'Перегазовка'],
            'release': ['Deceleration', '减速音', 'Замедление'],
            'revv': ['Acceleration', '加速音', 'Разгон'],
        }
        units = ('dB', '分贝', 'дБ')[column]
        for engine_prefix, counts in (('', (9, 7, 5)), ('engine_', (8, 7, 5))):
            for (family, labels), count in zip(families.items(), counts, strict=True):
                for number in range(1, count + 1):
                    sample = f'{engine_prefix}{family}-{number:03}'
                    values['config.create_fire_control.trackTrimDb.' + sample] = f'{labels[column]} {number:03} ({units})'
    # Generated variants use the same keys; conversion never touches persisted config paths or values.
    from opencc import OpenCC
    for language, conversion in (('zh_tw', 's2twp'), ('zh_hk', 's2hk')):
        converter = OpenCC(conversion)
        translations[language] = {key: converter.convert(value) for key, value in translations['zh_cn'].items()}
    for language, additions in translations.items():
        path = ROOT / 'src/main/resources/assets/create_fire_control/lang' / f'{language}.json'
        values = json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else {}
        values.pop('config.create_fire_control.proximityFuzeRadius', None)
        values.update(additions)
        path.write_text(json.dumps(values, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    merge()
