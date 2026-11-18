package com.hooya.stabilizedturret.content.armor;

import com.caca.copypoly.PolyBlockEntity;
import com.caca.copypoly.PolyMenu;
import com.caca.copypoly.PolyPackets;
import com.caca.copypoly.api.PolyShape;
import com.caca.copypoly.geometry.ShapeBounds;
import com.hooya.stabilizedturret.registry.PolyArmorMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** A snapshot of the connected cells and their revisions, owned by the player's active menu. */
public final class ArmorGroupMenu extends PolyMenu {
    public static final int MAX_BLOCKS = 32;
    public static final int CLEAR_HOLES_BUTTON = 0;
    private static final int MAX_OPEN_BYTES = 32600;

    public record Member(BlockPos pos, int revision) {}

    private record Grid(BlockPos origin, ShapeBounds bounds) {
        static Grid from(List<Member> members) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (var entry : members) {
                BlockPos pos = entry.pos;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return new Grid(new BlockPos(minX,minY,minZ),
                new ShapeBounds((maxX-minX+1)*16,(maxY-minY+1)*16,(maxZ-minZ+1)*16));
        }
    }

    private record Snapshot(BlockPos anchor, List<Member> members, Grid grid, PolyShape shape, boolean hasHoles) {
        int anchorRevision() {
            return members.stream().filter(m -> m.pos.equals(anchor)).findFirst().orElseThrow().revision;
        }

        static Snapshot read(RegistryFriendlyByteBuf buf) {
            BlockPos anchor = buf.readBlockPos();
            int count = buf.readVarInt();
            if (count < 2 || count > MAX_BLOCKS) throw invalid("multi_limit");
            var members = new ArrayList<Member>();
            for (int i = 0; i < count; i++) members.add(new Member(buf.readBlockPos(),buf.readVarInt()));
            var grid = Grid.from(members);
            var shape = PolyShape.fromPacked(buf.readVarIntArray(PolyShape.maxPacked(grid.bounds)),grid.bounds);
            return new Snapshot(anchor,List.copyOf(members),grid,shape,buf.readBoolean());
        }

        void write(RegistryFriendlyByteBuf buf, int[] data) {
            buf.writeBlockPos(anchor);
            buf.writeVarInt(members.size());
            for (var member : members) {
                buf.writeBlockPos(member.pos);
                buf.writeVarInt(member.revision);
            }
            buf.writeVarIntArray(data);
            buf.writeBoolean(hasHoles);
        }
    }

    private final Snapshot snapshot;
    private final Level level;
    private final Map<BlockPos,PolyBlockEntity> targets;
    private long lastEdit = Long.MIN_VALUE / 2;

    public ArmorGroupMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id,inv,Snapshot.read(buf),Map.of());
    }

    private ArmorGroupMenu(int id, Inventory inv, Snapshot snapshot, Map<BlockPos,PolyBlockEntity> targets) {
        super(PolyArmorMenus.GROUP_EDITOR.get(),id,inv,snapshot.anchor,snapshot.shape,snapshot.anchorRevision());
        this.snapshot = snapshot;
        this.targets = Map.copyOf(targets);
        this.level = inv.player.level();
    }

    public BlockPos origin() { return snapshot.grid.origin; }
    public List<Member> members() { return snapshot.members; }
    public boolean hasHoles() { return snapshot.hasHoles; }

    public static void open(ServerPlayer player, BlockPos pos, int revision) {
        if (!player.level().mayInteract(player,pos) || !player.mayBuild()) throw invalid("permission");
        if (!(player.level().getBlockEntity(pos) instanceof PolyBlockEntity anchor) || anchor.revision() != revision)
            throw invalid("changed");

        var group = collect(player,pos);
        if (group.size() < 2) throw invalid("multi_single");
        var snapshot = buildSnapshot(pos,group,Set.of());
        if (!present(player,snapshot,group,checkedData(snapshot))) throw invalid("permission");
    }

    private static ArmorDamage damage(PolyBlockEntity be) {
        return ((PolyArmorAccess)be).fireControl$armorDamage();
    }

    private static Snapshot buildSnapshot(BlockPos anchor,Map<BlockPos,PolyBlockEntity> group,Set<BlockPos> repaired) {
        var members = group.entrySet().stream().map(e ->
            new Member(e.getKey(),e.getValue().revision()+(repaired.contains(e.getKey())?1:0))).toList();
        var grid = Grid.from(members);
        var cells = new ArrayList<ArmorGroupGeometry.Cell>();
        boolean hasHoles = false;
        for (var member : members) {
            PolyBlockEntity be = group.get(member.pos);
            ArmorDamage current = damage(be);
            boolean repair = repaired.contains(member.pos);
            var controls = repair || !current.damaged() ? current.controlsWithoutHoles(be.document()) : current.editable(be.poly(),be.document());
            var mesh = repair ? current.meshWithoutHoles(be.poly()) : current.mesh(be.poly());
            cells.add(new ArmorGroupGeometry.Cell(member.pos.subtract(grid.origin),mesh,controls));
            if (!repair && !current.holes().isEmpty()) hasHoles = true;
        }
        return new Snapshot(anchor,members,grid,ArmorGroupGeometry.join(grid.bounds,cells),hasHoles);
    }

    private static int[] checkedData(Snapshot snapshot) {
        int[] data = snapshot.shape.packed();
        int bytes = 9 + VarInt.getByteSize(snapshot.members.size()) + VarInt.getByteSize(data.length);
        for (var member : snapshot.members) bytes += 8 + VarInt.getByteSize(member.revision);
        for (int value : data) bytes += VarInt.getByteSize(value);
        if (bytes > MAX_OPEN_BYTES) throw invalid("multi_complex");
        return data;
    }

    private static boolean present(ServerPlayer player,Snapshot snapshot,Map<BlockPos,PolyBlockEntity> group,int[] data) {
        return player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ArmorGroupMenu(id,inv,snapshot,group),
            Component.translatable("screen.copypoly.multi_title")), buf -> snapshot.write(buf,data)).isPresent();
    }

    private Map<BlockPos,PolyBlockEntity> checkCurrent(ServerPlayer player) {
        if (player.containerMenu != this || !stillValid(player)) throw invalid("permission");
        long tick = level.getGameTime();
        if (tick - lastEdit < 4) throw invalid("busy");
        lastEdit = tick;
        var live = collect(player,pos);
        if (!live.equals(targets)) throw invalid("changed");
        for (var member : members()) if (live.get(member.pos).revision() != member.revision) throw invalid("changed");
        return live;
    }

    @Override public boolean clickMenuButton(Player player,int button) {
        if (button != CLEAR_HOLES_BUTTON || !(player instanceof ServerPlayer server)) return false;
        try {
            var live = checkCurrent(server);
            var pierced = new LinkedHashSet<BlockPos>();
            for (var entry : live.entrySet()) if (!damage(entry.getValue()).holes().isEmpty()) {
                if (!entry.getValue().canBeginEdit()) throw invalid("busy");
                pierced.add(entry.getKey());
            }
            if (pierced.isEmpty()) throw invalid("changed");
            // Build and validate the replacement before modifying any cell.
            var repaired = buildSnapshot(pos,live,pierced);
            int[] data = checkedData(repaired);
            for (var p : pierced) damage(live.get(p)).clearHoles();
            for (var p : pierced) ((PolyArmorAccess)live.get(p)).fireControl$armorChanged();
            if (!present(server,repaired,live,data))
                PacketDistributor.sendToPlayer(server,new PolyPackets.Result(containerId,""));
        } catch (IllegalArgumentException rejected) {
            PacketDistributor.sendToPlayer(server,new PolyPackets.Result(containerId,rejected.getMessage()));
        }
        return true;
    }

    public String apply(ServerPlayer player, int[] packed) {
        try {
            var live = checkCurrent(player);
            for (var be : live.values()) if (!be.canBeginEdit()) return "busy";

            var draft = PolyShape.fromPacked(packed,document.bounds());
            if (draft.equals(document)) return "";
            var slices = ArmorGroupGeometry.cut(draft,members().stream().map(m -> m.pos.subtract(origin())).toList());
            var commits = new ArrayList<Runnable>();
            var notifications = new ArrayList<Runnable>();
            for (int i = 0; i < slices.size(); i++) {
                var be = live.get(members().get(i).pos);
                var armor = (PolyArmorAccess)be;
                var slice = slices.get(i);
                if (armor.fireControl$armorDamage().damaged()) {
                    commits.add(() -> armor.fireControl$armorDamage().install(slice.mesh()));
                    notifications.add(armor::fireControl$armorChanged);
                } else {
                    var next = slice.document();
                    next.compile();
                    commits.add(() -> be.stageDocument(next));
                    notifications.add(be::publishGeometryChange);
                }
            }
            // Validate all slices and revisions before notifying either the world or Sable.
            commits.forEach(Runnable::run);
            notifications.forEach(Runnable::run);
            return "";
        } catch (IllegalArgumentException rejected) {
            return rejected.getMessage();
        }
    }

    private static Map<BlockPos,PolyBlockEntity> collect(ServerPlayer player, BlockPos anchor) {
        Level level = player.level();
        var result = new LinkedHashMap<BlockPos,PolyBlockEntity>();
        var queue = new ArrayDeque<BlockPos>();
        var seen = new java.util.HashSet<BlockPos>();
        queue.add(anchor);
        Object scope = scope(level,anchor);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.remove();
            if (!seen.add(pos) || !level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof PolyBlockEntity block)
                    || block.isRemoved() || scope(level,pos) != scope) continue;
            if (!level.mayInteract(player,pos)) throw invalid("permission");
            if (result.size() >= MAX_BLOCKS) throw invalid("multi_limit");
            result.put(pos,block);
            for (Direction side : Direction.values()) queue.add(pos.relative(side));
        }
        return result;
    }

    private static Object scope(Level level, BlockPos pos) {
        return ModList.get().isLoaded("sable") ? SableScope.at(level,pos) : null;
    }

    private static final class SableScope {
        static Object at(Level level, BlockPos pos) {
            return dev.ryanhcode.sable.Sable.HELPER.getContaining(level,pos);
        }
    }

    private static IllegalArgumentException invalid(String reason) { return new IllegalArgumentException(reason); }
}
