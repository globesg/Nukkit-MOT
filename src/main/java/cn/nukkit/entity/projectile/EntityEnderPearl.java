package cn.nukkit.entity.projectile;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.entity.CreatureSpawnEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerTeleportEvent.TeleportCause;
import cn.nukkit.level.Position;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.NukkitMath;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.LevelEventPacket;
import cn.nukkit.utils.Utils;

public class EntityEnderPearl extends EntityProjectile {

    public static final int NETWORK_ID = 87;

    /**
     * 实体触碰珍珠的额外检测范围。
     *
     * 原版珍珠自身宽高只有 0.25，范围太小会漏掉高速风弹/投射物。
     * 0.35 比较适合模拟“碰到珍珠”。
     */
    private static final double ENTITY_TOUCH_EXPAND = 0.35D;

    @Override
    public int getNetworkId() {
        return NETWORK_ID;
    }

    @Override
    public float getWidth() {
        return 0.25f;
    }

    @Override
    public float getLength() {
        return 0.25f;
    }

    @Override
    public float getHeight() {
        return 0.25f;
    }

    @Override
    protected float getGravity() {
        return 0.03f;
    }

    @Override
    protected float getDrag() {
        return 0.01f;
    }

    public EntityEnderPearl(FullChunk chunk, CompoundTag nbt) {
        this(chunk, nbt, null);
    }

    public EntityEnderPearl(FullChunk chunk, CompoundTag nbt, Entity shootingEntity) {
        super(chunk, nbt, shootingEntity);
    }

    @Override
    public boolean onUpdate(int currentTick) {
        if (this.closed) {
            return false;
        }

        /*
         * 保留原来的撞方块逻辑：
         * 撞到方块时传送；
         * 撞到下界传送门时不传送；
         * mobsFromBlocks 开启时有概率生成螨虫。
         */
        if (this.isCollided && this.shootingEntity instanceof Player) {
            Block[] blocks = getCollisionHelper().getCollisionBlocks();
            boolean portal = false;

            for (Block collided : blocks) {
                if (collided.getId() == Block.NETHER_PORTAL) {
                    portal = true;
                    break;
                }
            }

            if (!portal) {
                teleport();
                trySpawnEndermite();
            }

            this.close();
            return false;
        }

        /*
         * 新增逻辑：
         * 珍珠每 tick 主动扫描附近实体。
         * 这样可以实现“任意实体碰到珍珠 -> 珍珠传送”。
         *
         * 注意：
         * 风弹这类投射物如果先更新，可能会先 attack 珍珠并 kill 自己，
         * 所以下面还额外重写了 attack(...)，专门处理这种情况。
         */
        if (this.shootingEntity instanceof Player && checkTouchedByAnyEntity()) {
            teleport();
            this.close();
            return false;
        }

        if (this.age > 1200 || this.isCollided) {
            this.close();
        }

        return super.onUpdate(currentTick);
    }

    /**
     * 新增逻辑：
     * 如果风弹、箭、雪球、其他实体通过 attack(...) 命中了珍珠，
     * 也直接触发珍珠传送。
     *
     * 这能修复“风弹撞珍珠时，走的是风弹逻辑，不走珍珠 onCollideWithEntity”的问题。
     */
    @Override
    public boolean attack(EntityDamageEvent source) {
        if (this.closed) {
            return false;
        }

        if (this.shootingEntity instanceof Player && source instanceof EntityDamageByEntityEvent damageByEntityEvent) {
            Entity damager = damageByEntityEvent.getDamager();

            // 防止刚扔出去就被发射者自己打回/碰到而立刻触发
            if (damager == this.shootingEntity && this.age < 5) {
                return false;
            }

            /*
             * EntityProjectile#attack 默认只接受 VOID 伤害。
             * 这里手动触发一次事件，给插件取消的机会。
             */
            this.server.getPluginManager().callEvent(source);
            if (source.isCancelled()) {
                return false;
            }

            teleport();
            this.close();
            return true;
        }

        return super.attack(source);
    }

    @Override
    public void onCollideWithEntity(Entity entity) {
        /*
         * 保留原生逻辑：
         * 珍珠主动撞到实体时传送。
         */
        if (this.shootingEntity instanceof Player) {
            teleport();
        }

        super.onCollideWithEntity(entity);
    }

    /**
     * 检测是否有任意实体碰到珍珠。
     */
    private boolean checkTouchedByAnyEntity() {
        for (Entity entity : this.level.getCollidingEntities(
                this.boundingBox.grow(ENTITY_TOUCH_EXPAND, ENTITY_TOUCH_EXPAND, ENTITY_TOUCH_EXPAND),
                this
        )) {
            if (entity == null || entity.closed || entity == this) {
                continue;
            }

            // 防止刚扔出去就碰到发射者自己
            if (entity == this.shootingEntity && this.age < 5) {
                continue;
            }

            // 观察者不触发
            if (entity instanceof Player player && player.getGamemode() == Player.SPECTATOR) {
                continue;
            }

            return true;
        }

        return false;
    }

    /**
     * 保留原来的螨虫生成逻辑。
     */
    private void trySpawnEndermite() {
        if (!Server.getInstance().mobsFromBlocks) {
            return;
        }

        if (Utils.rand(1, 20) != 5) {
            return;
        }

        Position spawnPos = add(0.5, 1, 0.5);

        CreatureSpawnEvent ev = new CreatureSpawnEvent(
                NETWORK_ID,
                spawnPos,
                CreatureSpawnEvent.SpawnReason.ENDER_PEARL,
                this.shootingEntity
        );

        level.getServer().getPluginManager().callEvent(ev);

        if (ev.isCancelled()) {
            return;
        }

        Entity entity = Entity.createEntity("Endermite", spawnPos);
        if (entity != null) {
            entity.spawnToAll();
        }
    }

    private void teleport() {
        if (!(this.shootingEntity instanceof Player player)) {
            return;
        }

        if (!this.level.equals(this.shootingEntity.getLevel())) {
            return;
        }

        this.level.addLevelEvent(
                this.shootingEntity.add(0.5, 0.5, 0.5),
                LevelEventPacket.EVENT_SOUND_ENDERMAN_TELEPORT
        );

        this.shootingEntity.teleport(
                new Vector3(
                        NukkitMath.floorDouble(this.x) + 0.5,
                        this.y,
                        NukkitMath.floorDouble(this.z) + 0.5
                ),
                TeleportCause.ENDER_PEARL
        );

        int gamemode = player.getGamemode();
        if (gamemode == Player.SURVIVAL || gamemode == Player.ADVENTURE) {
            this.shootingEntity.attack(new EntityDamageByEntityEvent(
                    this,
                    shootingEntity,
                    EntityDamageEvent.DamageCause.FALL,
                    5f,
                    0f
            ));
        }

        this.level.addLevelEvent(this, LevelEventPacket.EVENT_PARTICLE_ENDERMAN_TELEPORT);

        this.level.addLevelEvent(
                this.shootingEntity.add(0.5, 0.5, 0.5),
                LevelEventPacket.EVENT_SOUND_ENDERMAN_TELEPORT
        );
    }
}
