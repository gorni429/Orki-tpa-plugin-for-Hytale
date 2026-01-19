package com.example.exampleplugin;

import com.hypixel.hytale.builtin.teleport.components.TeleportHistory;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TpaCommand extends AbstractCommandCollection {
    private static final Map<UUID, PendingRequest> PENDING_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<UUID, ScheduledFuture<?>> COUNTDOWN_TASKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Vector3d> COUNTDOWN_POSITIONS = new ConcurrentHashMap<>();

    public TpaCommand() {
        super("tpa", "Teleport request command.");
        this.setPermissionGroup(GameMode.Adventure);
        this.addUsageVariant(new RequestVariant());
        this.addSubCommand(new AcceptSubCommand());
        this.addSubCommand(new DeclineSubCommand());
    }

    private static class PendingRequest {
        private final UUID requesterUuid;
        private final long createdAt;

        private PendingRequest(UUID requesterUuid, long createdAt) {
            this.requesterUuid = requesterUuid;
            this.createdAt = createdAt;
        }
    }

    public static class RequestVariant extends AbstractPlayerCommand {
        @Nonnull
        private final RequiredArg<PlayerRef> targetPlayerArg =
                this.withRequiredArg("targetPlayer", "server.commands.teleport.targetPlayer.desc", ArgTypes.PLAYER_REF);

        public RequestVariant() {
            super("Send a teleport request to another player.");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PlayerRef targetPlayerRef = this.targetPlayerArg.get(context);
            if (targetPlayerRef.getUuid().equals(playerRef.getUuid())) {
                playerRef.sendMessage(Message.raw("You cannot send a teleport request to yourself."));
                return;
            }
            Ref<EntityStore> targetRef = targetPlayerRef.getReference();
            if (targetRef == null || !targetRef.isValid()) {
                playerRef.sendMessage(Message.translation("server.commands.errors.targetNotInWorld"));
                return;
            }
            PENDING_REQUESTS.put(targetPlayerRef.getUuid(), new PendingRequest(playerRef.getUuid(), System.currentTimeMillis()));
            playerRef.sendMessage(Message.raw("You sent a teleport request to " + targetPlayerRef.getUsername() + "."));
            targetPlayerRef.sendMessage(Message.raw(playerRef.getUsername() + " wants to teleport to you. Use /tpa accept or /tpa decline."));
        }
    }

    public static class AcceptSubCommand extends AbstractPlayerCommand {
        public AcceptSubCommand() {
            super("accept", "Accept pending teleport request.");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PendingRequest pending = PENDING_REQUESTS.remove(playerRef.getUuid());
            if (pending == null) {
                playerRef.sendMessage(Message.raw("You do not have any pending teleport requests."));
                return;
            }
            PlayerRef requesterPlayerRef = Universe.get().getPlayer(pending.requesterUuid);
            if (requesterPlayerRef == null) {
                playerRef.sendMessage(Message.raw("The player who sent the request is offline."));
                return;
            }
            Ref<EntityStore> requesterRef = requesterPlayerRef.getReference();
            if (requesterRef == null || !requesterRef.isValid()) {
                playerRef.sendMessage(Message.raw("The player who sent the request is no longer in a world."));
                return;
            }
            Store<EntityStore> requesterStore = requesterRef.getStore();
            World requesterWorld = requesterStore.getExternalData().getWorld();
            TransformComponent requesterTransform = requesterStore.getComponent(requesterRef, TransformComponent.getComponentType());
            HeadRotation requesterHeadRotation = requesterStore.getComponent(requesterRef, HeadRotation.getComponentType());
            if (requesterTransform == null || requesterHeadRotation == null) {
                return;
            }
            Vector3d previousPos = requesterTransform.getPosition().clone();
            Vector3f previousRot = requesterHeadRotation.getRotation().clone();
            UUID requesterUuid = requesterPlayerRef.getUuid();
            ScheduledFuture<?> existing = COUNTDOWN_TASKS.remove(requesterUuid);
            if (existing != null) {
                existing.cancel(false);
            }
            COUNTDOWN_POSITIONS.remove(requesterUuid);
            COUNTDOWN_POSITIONS.put(requesterUuid, requesterTransform.getPosition().clone());
            Store<EntityStore> targetStore = ref.getStore();
            World targetWorld = targetStore.getExternalData().getWorld();
            requesterPlayerRef.sendMessage(Message.raw("Your teleport request to " + playerRef.getUsername() + " has been accepted. Teleporting in 5 seconds. Do not move."));
            playerRef.sendMessage(Message.raw("You accepted the teleport request from " + requesterPlayerRef.getUsername() + ". Teleporting them in 5 seconds."));
            int[] secondsLeft = new int[]{5};
            final ScheduledFuture<?>[] countdownHolder = new ScheduledFuture<?>[1];
            countdownHolder[0] = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                ScheduledFuture<?> self = countdownHolder[0];
                requesterWorld.execute(() -> {
                    if (self != null && self.isCancelled()) {
                        return;
                    }
                    Ref<EntityStore> currentRequesterRef = requesterPlayerRef.getReference();
                    if (currentRequesterRef == null || !currentRequesterRef.isValid()) {
                        COUNTDOWN_POSITIONS.remove(requesterUuid);
                        COUNTDOWN_TASKS.remove(requesterUuid);
                        if (self != null) {
                            self.cancel(false);
                        }
                        return;
                    }
                    Store<EntityStore> currentStore = currentRequesterRef.getStore();
                    TransformComponent currentTransform = currentStore.getComponent(currentRequesterRef, TransformComponent.getComponentType());
                    if (currentTransform == null) {
                        COUNTDOWN_POSITIONS.remove(requesterUuid);
                        COUNTDOWN_TASKS.remove(requesterUuid);
                        if (self != null) {
                            self.cancel(false);
                        }
                        return;
                    }
                    Vector3d startPos = COUNTDOWN_POSITIONS.get(requesterUuid);
                    if (startPos == null) {
                        startPos = currentTransform.getPosition().clone();
                        COUNTDOWN_POSITIONS.put(requesterUuid, startPos);
                    }
                    Vector3d currentPos = currentTransform.getPosition();
                    double distanceSq = currentPos.distanceSquaredTo(startPos);
                    if (distanceSq > 0.01) {
                        requesterPlayerRef.sendMessage(Message.raw("Teleportation cancelled because you moved."));
                        playerRef.sendMessage(Message.raw(requesterPlayerRef.getUsername() + " moved, teleportation has been cancelled."));
                        COUNTDOWN_POSITIONS.remove(requesterUuid);
                        COUNTDOWN_TASKS.remove(requesterUuid);
                        if (self != null) {
                            self.cancel(false);
                        }
                        return;
                    }
                    NotificationUtil.sendNotification(requesterPlayerRef.getPacketHandler(), "Teleporting in " + secondsLeft[0] + " second" + (secondsLeft[0] == 1 ? "" : "s") + "...", NotificationStyle.Warning);
                    secondsLeft[0]--;
                    if (secondsLeft[0] <= 0) {
                        COUNTDOWN_POSITIONS.remove(requesterUuid);
                        COUNTDOWN_TASKS.remove(requesterUuid);
                        if (self != null) {
                            self.cancel(false);
                        }
                        Ref<EntityStore> latestTargetRef = playerRef.getReference();
                        if (latestTargetRef == null || !latestTargetRef.isValid()) {
                            requesterPlayerRef.sendMessage(Message.raw("Teleportation failed: target player is no longer in a world."));
                            playerRef.sendMessage(Message.raw("Teleportation failed because you are no longer in a world."));
                            return;
                        }
                        Store<EntityStore> latestTargetStore = latestTargetRef.getStore();
                        World latestTargetWorld = latestTargetStore.getExternalData().getWorld();
                        latestTargetWorld.execute(() -> {
                            TransformComponent targetTransform = latestTargetStore.getComponent(latestTargetRef, TransformComponent.getComponentType());
                            if (targetTransform == null) {
                                return;
                            }
                            Vector3d targetPos = targetTransform.getPosition().clone();
                            Vector3f targetBodyRot = targetTransform.getRotation().clone();
                            requesterWorld.execute(() -> {
                                Teleport teleport = new Teleport(latestTargetWorld, targetPos, targetBodyRot);
                                requesterStore.addComponent(requesterRef, Teleport.getComponentType(), teleport);
                                requesterStore.ensureAndGetComponent(requesterRef, TeleportHistory.getComponentType()).append(requesterWorld, previousPos, previousRot, "TPA to " + playerRef.getUsername());
                                requesterPlayerRef.sendMessage(Message.raw("Teleporting now to " + playerRef.getUsername() + "."));
                                playerRef.sendMessage(Message.raw(requesterPlayerRef.getUsername() + " has been teleported to you."));
                            });
                        });
                    }
                });
            }, 0L, 1L, TimeUnit.SECONDS);
            COUNTDOWN_TASKS.put(requesterUuid, countdownHolder[0]);
        }
    }

    public static class DeclineSubCommand extends AbstractPlayerCommand {
        public DeclineSubCommand() {
            super("decline", "Decline pending teleport request.");
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
            PendingRequest pending = PENDING_REQUESTS.remove(playerRef.getUuid());
            if (pending == null) {
                playerRef.sendMessage(Message.raw("You do not have any pending teleport requests."));
                return;
            }
            PlayerRef requesterPlayerRef = Universe.get().getPlayer(pending.requesterUuid);
            if (requesterPlayerRef != null) {
                requesterPlayerRef.sendMessage(Message.raw(playerRef.getUsername() + " declined your teleport request."));
                playerRef.sendMessage(Message.raw("You declined the teleport request from " + requesterPlayerRef.getUsername() + "."));
            } else {
                playerRef.sendMessage(Message.raw("You declined the teleport request, but the player is offline."));
            }
        }
    }
}

