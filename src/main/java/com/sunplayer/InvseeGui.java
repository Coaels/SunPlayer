package com.sunplayer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /sunplayer <name> invsee}: a chest window onto a fake player's inventory.
 *
 * <p>The window is a private custom {@link Inventory}, never the fake's own container. That matters
 * on two counts:
 * <ul>
 *   <li>A fake player has no client and therefore must never be handed a real menu — a container
 *       open on a {@code ServerPlayer} crashes its entity tick on Folia (see
 *       {@link ActionPack#doUse}, which refuses to open one). Two players editing one real
 *       container would also share the same slots, which is exactly what "invsee" must not do.</li>
 *   <li>Every change is copied back one slot at a time, and only for the slots the viewer actually
 *       changed. Writing the whole window back would delete an item the fake picked up while the
 *       window was open.</li>
 * </ul>
 *
 * <p>Threads (Folia): the reading of the fake's inventory and the writing of it happen on the
 * region that owns the fake, the window is only ever read on the region that owns the viewer.
 * The event handlers run on the viewer's thread and hand snapshots across.
 */
public final class InvseeGui implements Listener {

    /** Six rows: one for equipment, three for the main inventory, one for the hotbar, one decorative. */
    private static final int GUI_ROWS = 6;
    private static final int GUI_SLOTS = GUI_ROWS * 9;

    /** Player inventory slots: 0-35 items, 36 boots, 37 leggings, 38 chest, 39 helmet, 40 offhand. */
    private static final int INV_SLOTS = 41;

    /**
     * Window slot -> player inventory slot, or {@code -1} for "decoration, not a real slot".
     *
     * <p>Laid out like the player's own screen: armour and offhand across the top, the 27 storage
     * slots in the middle, the hotbar under them. The middle block is a straight copy (window slot
     * 9-35 = inventory slot 9-35), which keeps the mapping easy to eyeball.
     */
    private static final int[] GUI_TO_INV = new int[GUI_SLOTS];

    /** The reverse mapping, built once: player inventory slot -> window slot. */
    private static final int[] INV_TO_GUI = new int[INV_SLOTS];

    static {
        java.util.Arrays.fill(GUI_TO_INV, -1);
        java.util.Arrays.fill(INV_TO_GUI, -1);
        GUI_TO_INV[0] = 39; // helmet
        GUI_TO_INV[1] = 38; // chestplate
        GUI_TO_INV[2] = 37; // leggings
        GUI_TO_INV[3] = 36; // boots
        GUI_TO_INV[4] = 40; // off-hand
        for (int i = 0; i < 27; i++) {
            GUI_TO_INV[9 + i] = 9 + i;
        }
        for (int i = 0; i < 9; i++) {
            GUI_TO_INV[36 + i] = i;
        }
        for (int slot = 0; slot < GUI_SLOTS; slot++) {
            if (GUI_TO_INV[slot] >= 0) {
                INV_TO_GUI[GUI_TO_INV[slot]] = slot;
            }
        }
    }

    private final SunPlayerPlugin plugin;
    private final NamespacedKey decoKey;
    /** Viewer uuid -> the window that viewer currently has open. One window per viewer. */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public InvseeGui(SunPlayerPlugin plugin) {
        this.plugin = plugin;
        this.decoKey = new NamespacedKey(plugin, "invsee_deco");
    }

    /** One open window: which fake it mirrors, the inventory itself and what the fake last held. */
    private static final class Session {
        final UUID viewerId;
        final String fakeName;
        final Inventory gui;
        /** What the fake is believed to hold, index = player inventory slot. Only touched on its region. */
        final ItemStack[] last = new ItemStack[INV_SLOTS];
        /** Set once the window is gone, so late events cannot apply a stale snapshot. */
        boolean closed;

        Session(UUID viewerId, String fakeName, Inventory gui) {
            this.viewerId = viewerId;
            this.fakeName = fakeName;
            this.gui = gui;
        }
    }

    // ------------------------------------------------------------------- open

    /**
     * Opens the window for {@code viewer}. Must be called from the viewer's thread (a command is).
     *
     * <p>The inventory is read on the fake's region first, then the window is created and opened on
     * the viewer's region, so neither thread ever touches state that belongs to the other.
     */
    public void open(Player viewer, FakePlayer fake) {
        // Defence in depth: the command layer already refuses bots, but any plugin-side caller
        // (a menu, an admin tool) must not get the window either. A bot's gear is a copy of its
        // owner's inventory, so letting it out would duplicate the owner's items.
        if (fake.pvpBot()) {
            this.tell(viewer, "PVP 机器人的物品是复制品，不能打开物品栏。");
            return;
        }
        String bare = this.plugin.manager().bareNameOf(fake);
        this.onFake(fake, () -> {
            Player bukkit = fake.getBukkitEntity();
            ItemStack[] contents = read(bukkit);

            Inventory gui = Bukkit.createInventory(null, GUI_SLOTS, title(bare));
            Session session = new Session(viewer.getUniqueId(), bare, gui);
            for (int slot = 0; slot < INV_SLOTS; slot++) {
                gui.setItem(invToGui(slot), copy(contents[slot]));
                session.last[slot] = copy(contents[slot]);
            }
            decoration(gui);

            this.onViewer(viewer, () -> this.show(viewer, session));
        }, () -> this.tell(viewer, "假人 " + bare + " 已经下线，无法打开物品栏。"));
    }

    /** Runs on the viewer's region: drops any window they had and shows the new one. */
    private void show(Player viewer, Session session) {
        if (!viewer.isOnline()) {
            return;
        }
        Session previous = this.sessions.remove(viewer.getUniqueId());
        if (previous != null && previous != session) {
            // Closing the old window must still write its last changes back; openInventory fires
            // InventoryCloseEvent, but by then the session map already points at the new window.
            this.flush(viewer, previous);
        }
        this.sessions.put(viewer.getUniqueId(), session);
        viewer.openInventory(session.gui);
        debug("invsee 打开 " + session.fakeName + " 给 " + viewer.getName()
                + "：界面类型=" + viewer.getOpenInventory().getType()
                + " 大小=" + viewer.getOpenInventory().getTopInventory().getSize());
        // Five ticks later the window must still be there; a plugin that cancels InventoryOpenEvent
        // closes it immediately, and then every click is silently dropped by the server.
        this.onViewerDelayed(viewer, () -> {
            if (this.sessions.get(viewer.getUniqueId()) != session) {
                return;
            }
            debug("invsee 5 tick 后仍打开？界面类型=" + viewer.getOpenInventory().getType()
                    + " 大小=" + viewer.getOpenInventory().getTopInventory().getSize());
        });
    }

    /** Fills the slots that do not exist on a player with grey panes plus one explanation sheet. */
    private void decoration(Inventory gui) {
        ItemStack pane = deco(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < GUI_SLOTS; slot++) {
            if (GUI_TO_INV[slot] < 0) {
                gui.setItem(slot, pane);
            }
        }
        ItemStack help = deco(Material.PAPER, "说明");
        ItemMeta meta = help.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            lore.add(line("第一行：头盔、胸甲、护腿、靴子、副手"));
            lore.add(line("中间三行：背包 9-35 格"));
            lore.add(line("第五行：快捷栏 0-8 格"));
            lore.add(line("改动会立刻写进假人的物品栏"));
            meta.lore(lore);
            help.setItemMeta(meta);
        }
        gui.setItem(49, help);
    }

    private static Component line(String text) {
        return Component.text(text).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component title(String fakeName) {
        return Component.text("[假人] ").color(NamedTextColor.GRAY)
                .append(Component.text(fakeName).color(NamedTextColor.WHITE))
                .append(Component.text(" 的物品栏").color(NamedTextColor.DARK_GRAY));
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Session session = this.sessionOf(event.getWhoClicked());
        if (session == null) {
            return;
        }
        if (!sameWindow(event.getView().getTopInventory(), session.gui)) {
            debug("invsee 点击忽略：窗口不是我们打开的那个（" + event.getView().getTopInventory()
                    + " != " + session.gui + "）");
            return;
        }
        int raw = event.getRawSlot();
        // A decoration slot is not a real slot: taking the pane out would silently do nothing.
        if (raw >= 0 && raw < GUI_SLOTS && GUI_TO_INV[raw] < 0) {
            debug("invsee 点击拒绝：装饰槽位 " + raw);
            event.setCancelled(true);
            return;
        }
        // Cursor or hotbar holds one of our panes (a shift-clicked pane, a stale cursor copy).
        if (isDeco(event.getCurrentItem()) || isDeco(event.getCursor())) {
            debug("invsee 点击拒绝：装饰物品被拿走或放回");
            event.setCancelled(true);
            return;
        }
        debug("invsee 点击 原始槽位=" + raw + " 背包槽位="
                + (raw >= 0 && raw < GUI_SLOTS ? GUI_TO_INV[raw] : "（玩家背包）"));
        // The click is applied by vanilla *after* this handler, so the window is read next tick.
        this.scheduleFlush(event.getWhoClicked(), session);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onOpenMonitor(org.bukkit.event.inventory.InventoryOpenEvent event) {
        Session session = this.sessionOf(event.getPlayer());
        if (session == null || !sameWindow(event.getView().getTopInventory(), session.gui)) {
            return;
        }
        debug("invsee InventoryOpenEvent 处理完毕: 取消=" + event.isCancelled());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Session session = this.sessionOf(event.getWhoClicked());
        if (session == null || !sameWindow(event.getView().getTopInventory(), session.gui)) {
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < GUI_SLOTS && GUI_TO_INV[raw] < 0) {
                event.setCancelled(true);
                return;
            }
        }
        if (isDeco(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        debug("invsee 拖拽 槽位=" + event.getRawSlots());
        this.scheduleFlush(event.getWhoClicked(), session);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Session session = this.sessionOf(event.getPlayer());
        debug("invsee 界面关闭: " + event.getView().getType()
                + " 大小=" + event.getView().getTopInventory().getSize()
                + " 是本插件的窗口=" + (session != null && sameWindow(event.getView().getTopInventory(), session.gui)));
        if (session == null || !sameWindow(event.getView().getTopInventory(), session.gui)) {
            return;
        }
        this.sessions.remove(session.viewerId, session);
        if (event.getPlayer() instanceof Player player) {
            this.flush(player, session);
        }
    }

    // ------------------------------------------------------------------ sync

    /**
     * Reads the window on the viewer's thread one tick later and writes the differences back.
     *
     * <p>Delayed on purpose: {@code InventoryClickEvent} fires before vanilla applies the click, so
     * reading the window inside the handler would copy the state from *before* the click.
     */
    private void scheduleFlush(org.bukkit.entity.HumanEntity viewer, Session session) {
        if (!(viewer instanceof Player player)) {
            return;
        }
        this.onViewerDelayed(player, () -> {
            debug("invsee 点击后 1 tick 同步检查: 会话仍在=" + !session.closed);
            if (!session.closed) {
                this.flush(player, session);
            }
        });
    }

    /** Snapshot the window (viewer thread), then apply it to the fake (fake thread). */
    private void flush(Player viewer, Session session) {
        Inventory gui = session.gui;
        ItemStack[] snapshot = new ItemStack[INV_SLOTS];
        for (int slot = 0; slot < INV_SLOTS; slot++) {
            snapshot[slot] = copy(gui.getItem(invToGui(slot)));
        }
        FakePlayer fake = this.plugin.manager().get(session.fakeName);
        if (fake == null) {
            return;
        }
        this.onFake(fake, () -> {
            if (fake.isRemoved()) {
                return;
            }
            apply(fake, session, snapshot);
        }, () -> {
            // The fake is gone; the window is closed by closeFor()/remove() anyway.
        });
    }

    /** Runs on the fake's region thread: writes only the slots this window actually changed. */
    private static void apply(FakePlayer fake, Session session, ItemStack[] snapshot) {
        Player bukkit = fake.getBukkitEntity();
        Inventory inventory = bukkit.getInventory();
        int changed = 0;
        for (int slot = 0; slot < INV_SLOTS; slot++) {
            if (same(snapshot[slot], session.last[slot])) {
                continue;
            }
            debug("invsee 写回 " + session.fakeName + " 槽位 " + slot + ": "
                    + describe(session.last[slot]) + " -> " + describe(snapshot[slot]));
            inventory.setItem(slot, snapshot[slot] == null ? null : snapshot[slot].clone());
            session.last[slot] = copy(snapshot[slot]);
            changed++;
        }
        if (changed > 0) {
            debug("invsee 同步: " + session.fakeName + " 共修改 " + changed + " 个槽位");
        }
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Closes every window that mirrors {@code fakeName}; called when the fake is removed.
     *
     * <p>Without this the viewer would keep an editable copy of the inventory of a player that no
     * longer exists, and the writes would land in a static map entry forever.
     */
    public void closeFor(String fakeName) {
        String bare = this.plugin.manager().bareName(fakeName);
        for (Session session : new ArrayList<>(this.sessions.values())) {
            if (!session.fakeName.equalsIgnoreCase(bare)) {
                continue;
            }
            session.closed = true;
            this.sessions.remove(session.viewerId, session);
            Player viewer = Bukkit.getPlayer(session.viewerId);
            if (viewer != null) {
                this.onViewer(viewer, () -> {
                    if (sameWindow(viewer.getOpenInventory().getTopInventory(), session.gui)) {
                        viewer.closeInventory();
                    }
                });
            }
        }
    }

    /** Closes everything; used on disable so no window outlives the plugin. */
    public void closeAll() {
        for (Session session : new ArrayList<>(this.sessions.values())) {
            session.closed = true;
            this.sessions.remove(session.viewerId, session);
            Player viewer = Bukkit.getPlayer(session.viewerId);
            if (viewer != null) {
                try {
                    this.onViewer(viewer, () -> {
                        if (sameWindow(viewer.getOpenInventory().getTopInventory(), session.gui)) {
                            viewer.closeInventory();
                        }
                    });
                } catch (Throwable ignored) {
                    // Shutting down: a window that stays open is closed by the server anyway.
                }
            }
        }
    }

    // ---------------------------------------------------------------- threads

    /** Runs on the fake's region thread (immediately when we are already on it). */
    private void onFake(FakePlayer fake, Runnable task, Runnable gone) {
        Player handle = fake.getBukkitEntity();
        if (this.ownedByCurrentRegion(handle)) {
            if (fake.isRemoved()) {
                gone.run();
                return;
            }
            task.run();
            return;
        }
        ServerCompat.runOnEntity(this.plugin, handle, task, gone);
    }

    private void onViewer(Player viewer, Runnable task) {
        if (this.ownedByCurrentRegion(viewer)) {
            task.run();
            return;
        }
        ServerCompat.runOnEntity(this.plugin, viewer, task, null);
    }

    private void onViewerDelayed(Player viewer, Runnable task) {
        ServerCompat.runOnEntityDelayed(this.plugin, viewer, task, 1L, null);
    }

    /** {@code true} when the calling thread may touch this entity (always true on Paper). */
    private boolean ownedByCurrentRegion(Player player) {
        if (!ServerCompat.FOLIA) {
            return true;
        }
        try {
            return Bukkit.isOwnedByCurrentRegion(player);
        } catch (Throwable t) {
            return false;
        }
    }

    private void tell(Player viewer, String message) {
        this.onViewer(viewer, () -> viewer.sendMessage("[SunPlayer] " + message));
    }

    // ---------------------------------------------------------------- helpers

    private Session sessionOf(org.bukkit.entity.HumanEntity viewer) {
        return viewer == null ? null : this.sessions.get(viewer.getUniqueId());
    }

    /**
     * Whether {@code top} is the window this session created.
     *
     * <p>CraftBukkit may hand back a fresh wrapper around the same custom container — the view's
     * top inventory is not guaranteed to be the very instance that was passed to
     * {@code openInventory} — so identity alone is not enough: {@code CraftInventory#equals}
     * compares the underlying container, which is the same object in that case. A window that is
     * not ours (a chest the viewer opened, another plugin's menu) never compares equal.
     */
    private static boolean sameWindow(Inventory top, Inventory ours) {
        if (top == ours) {
            return true;
        }
        try {
            return top != null && top.equals(ours);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void debug(String message) {
        if (FakeConnection.debugMessages) {
            Bukkit.getLogger().info("[SunPlayer][debug] " + message);
        }
    }

    /** Item for a log line: "空", "3x DIAMOND_SWORD". */
    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "空";
        }
        return stack.getAmount() + "x " + stack.getType();
    }

    /** Window slot for a player inventory slot; always 0-53 for the 41 real slots. */
    private static int invToGui(int invSlot) {
        return INV_TO_GUI[invSlot];
    }

    /** Reads the fake's 41 inventory slots (armour and off-hand included). */
    private static ItemStack[] read(Player bukkit) {
        ItemStack[] out = new ItemStack[INV_SLOTS];
        Inventory inventory = bukkit.getInventory();
        for (int slot = 0; slot < INV_SLOTS; slot++) {
            out[slot] = copy(inventory.getItem(slot));
        }
        return out;
    }

    private ItemStack deco(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(this.decoKey, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private boolean isDeco(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(this.decoKey, PersistentDataType.BYTE);
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.clone();
    }

    /** Same item, same amount. A null and an empty stack are the same thing. */
    private static boolean same(ItemStack a, ItemStack b) {
        boolean emptyA = a == null || a.isEmpty();
        boolean emptyB = b == null || b.isEmpty();
        if (emptyA || emptyB) {
            return emptyA && emptyB;
        }
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }
}
