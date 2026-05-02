package cn.nukkit.entity.projectile;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityLiving;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.particle.GenericParticle;
import cn.nukkit.level.particle.Particle;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.LevelSoundEventPacket;
import cn.nukkit.network.protocol.PlaySoundPacket;

/**
 * @author glorydark
 */
public class EntityWindCharge extends EntityProjectile {

    public static final int NETWORK_ID = 143;

    public Entity directionChanged;

    public EntityWindCharge(FullChunk chunk, CompoundTag nbt) {
        this(chunk, nbt, null);
    }

    public EntityWindCharge(FullChunk chunk, CompoundTag nbt, Entity shootingEntity) {
        super(chunk, nbt, shootingEntity);
    }

    @Override
    public int getNetworkId() {
        return NETWORK_ID;
    }

    @Override
    public void onCollideWithEntity(Entity entity) {
        if (directionChanged != null && directionChanged == entity) {
            return;
        }

        // 风弹撞珍珠：让珍珠执行传送，同时风弹播放爆炸效果
        if (entity instanceof EntityEnderPearl pearl) {
            pearl.onCollideWithEntity(this);
            burst();
            return;
        }

        entity.attack(new EntityDamageByEntityEvent(
                this,
                entity,
                EntityDamageEvent.DamageCause.PROJECTILE,
                1f
        ));

        knockBack(entity);
        burst();
    }

    @Override
    public void onHit() {
        for (Entity entity : level.getEntities()) {
            if (entity instanceof EntityLiving entityLiving) {
                if (entityLiving.distance(this) < getBurstRadius()) {
                    this.knockBack(entityLiving);
                }
            }
        }

        burst();
    }

    /**
     * 统一风弹爆炸效果：
     * 1. 发送 LevelSoundEventPacket 的 WIND_CHARGE_BURST；
     * 2. 发送 PlaySoundPacket 字符串音效 wind_charge.burst 兜底；
     * 3. 生成风弹爆炸粒子；
     * 4. kill 风弹。
     */
    public void burst() {
        if (this.closed) {
            return;
        }

        this.level.addLevelSoundEvent(
                this.add(0, 1),
                LevelSoundEventPacket.SOUND_WIND_CHARGE_BURST
        );

        // 兜底：部分客户端/协议下 LevelSoundEvent 可能没有声音，直接播放 Bedrock 字符串音效
        playWindChargeBurstFallback();

        this.level.addParticle(new GenericParticle(
                this,
                Particle.TYPE_WIND_EXPLOSION
        ));

        this.kill();
    }

    private void playWindChargeBurstFallback() {
        PlaySoundPacket pk = new PlaySoundPacket();
        pk.name = "wind_charge.burst";
        pk.x = (int) Math.floor(this.x);
        pk.y = (int) Math.floor(this.y);
        pk.z = (int) Math.floor(this.z);
        pk.volume = 4.0f;
        pk.pitch = 1.0f;

        Server.broadcastPacket(
                this.level.getChunkPlayers(this.getFloorX() >> 4, this.getFloorZ() >> 4).values(),
                pk
        );
    }

    @Override
    public boolean attack(EntityDamageEvent source) {
        if (this.directionChanged == null && source instanceof EntityDamageByEntityEvent event) {
            this.directionChanged = event.getDamager();
            this.setMotion(event.getDamager().getDirectionVector());
            this.level.addParticle(new GenericParticle(
                    event.getDamager(),
                    Particle.TYPE_WIND_EXPLOSION
            ));
        }

        return true;
    }

    @Override
    public boolean onUpdate(int currentTick) {
        if (this.closed) {
            return false;
        }

        // 先主动检测附近珍珠，避免两个小投射物高速相撞时漏判
        if (checkEnderPearlCollision()) {
            return false;
        }

        boolean hasUpdate = super.onUpdate(currentTick);

        if (this.age > 1200 || this.isCollided) {
            this.kill();
            hasUpdate = true;
        }

        return hasUpdate;
    }

    private boolean checkEnderPearlCollision() {
        double radius = 1.0D;
        double radiusSquared = radius * radius;

        for (Entity entity : this.level.getEntities()) {
            if (!(entity instanceof EntityEnderPearl pearl)) {
                continue;
            }

            if (pearl.closed) {
                continue;
            }

            if (pearl.getLevel() != this.getLevel()) {
                continue;
            }

            if (this.distanceSquared(pearl) <= radiusSquared) {
                pearl.onCollideWithEntity(this);
                burst();
                return true;
            }
        }

        return false;
    }

    @Override
    public float getWidth() {
        return 0.3125f;
    }

    @Override
    public float getLength() {
        return 0.3125f;
    }

    @Override
    public float getHeight() {
        return 0.3125f;
    }

    @Override
    protected float getGravity() {
        return 0.00f;
    }

    @Override
    protected float getDrag() {
        return 0.01f;
    }

    public double getBurstRadius() {
        return 2f;
    }

    public double getKnockbackStrength() {
        return 0.2f;
    }

    protected void knockBack(Entity entity) {
        Vector3 knockback = new Vector3(entity.motionX, entity.motionY, entity.motionZ);

        knockback.x /= 2d;
        knockback.y /= 2d;
        knockback.z /= 2d;

        knockback.x -= (this.getX() - entity.getX()) * getKnockbackStrength();
        knockback.y += 1.0f;
        knockback.z -= (this.getZ() - entity.getZ()) * getKnockbackStrength();

        entity.setMotion(knockback);
    }

    @Override
    public String getName() {
        return "Wind Charge Projectile";
    }
}
