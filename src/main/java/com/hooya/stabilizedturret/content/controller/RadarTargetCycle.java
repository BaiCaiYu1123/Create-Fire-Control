package com.hooya.stabilizedturret.content.controller;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

final class RadarTargetCycle {
    static @Nullable UUID next(List<StabilizerControllerBlockEntity.RadarContact> contacts,
                               @Nullable UUID current) {
        if (contacts.isEmpty()) return current;
        if (current != null) {
            for (int index = 0; index < contacts.size(); index++) {
                var contact = contacts.get(index);
                if (contact.subLevelId().equals(current) || contact.memberIds().contains(current)) {
                    return contacts.get((index + 1) % contacts.size()).subLevelId();
                }
            }
        }
        return contacts.get(0).subLevelId();
    }

    private RadarTargetCycle() {}
}
