package cn.nukkit.entity.projectile;

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

        /*
         * 风弹撞到末影珍珠：
         * 让珍珠执行自己的碰撞/传送逻辑，
         * 然后风弹播放爆炸声音 + 粒子 + kill。
         */
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
     * 统一风弹爆炸效果。
     *
     * 原来声音逻辑分散在 onCollideWithEntity 和 onHit 里。
     * 现在珍珠主动扫描到风弹时，也可以直接调用这个方法，
     * 避免“珍珠传送了，但风弹没有爆炸声音”。
     */
    public void burst() {
        if (this.closed) {
            return;
        }

        this.level.addLevelSoundEvent(
                this.add(0, 1),
                LevelSoundEventPacket.SOUND_WIND_CHARGE_BURST
        );

        this.level.addParticle(new GenericParticle(
                this,
                Particle.TYPE_WIND_EXPLOSION
        ));

        this.kill();
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

        /*
         * 先主动检测附近珍珠。
         * 因为风弹和珍珠碰撞箱都很小，父类 EntityProjectile 的线段碰撞
         * 可能在高速相撞时漏判。父类确实是通过移动线段查近实体再调用
         * onCollideWithEntity(...)。这里做一层额外兜底。
         */
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
