package io.github.apace100.origins.component;

import io.github.apace100.origins.Origins;
import io.github.apace100.origins.origin.Origin;
import io.github.apace100.origins.origin.OriginLayer;
import io.github.apace100.origins.origin.OriginLayers;
import io.github.apace100.origins.origin.OriginRegistry;
import io.github.apace100.origins.power.Power;
import io.github.apace100.origins.power.PowerType;
import io.github.apace100.origins.registry.ModComponents;
import io.github.apace100.origins.registry.ModRegistries;
import nerdhub.cardinal.components.api.ComponentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.*;

public class PlayerOriginComponent implements OriginComponent {

    private PlayerEntity player;
    private HashMap<OriginLayer, Origin> origins = new HashMap<>();
    private HashMap<PowerType<?>, Power> powers = new HashMap<>();

    private boolean hadOriginBefore = false;

    public PlayerOriginComponent(PlayerEntity player) {
        this.player = player;
    }

    @Override
    public boolean hasAllOrigins() {
        return OriginLayers.getLayers().stream().allMatch(layer -> {
            return !layer.isEnabled() || (origins.containsKey(layer) && origins.get(layer) != null && origins.get(layer) != Origin.EMPTY);
        });
    }

    @Override
    public HashMap<OriginLayer, Origin> getOrigins() {
        return origins;
    }

    @Override
    public boolean hasOrigin(OriginLayer layer) {
        return origins != null && origins.containsKey(layer) && origins.get(layer) != null && origins.get(layer) != Origin.EMPTY;
    }

    @Override
    public Origin getOrigin(OriginLayer layer) {
        if(!origins.containsKey(layer)) {
            return null;
        }
        return origins.get(layer);
    }

    @Override
    public boolean hadOriginBefore() {
        return hadOriginBefore;
    }

    @Override
    public boolean hasPower(PowerType<?> powerType) {
        return powers.containsKey(powerType);
    }

    private boolean hasPowerType(PowerType<?> powerType) {
        return origins.values().stream().anyMatch(o -> o.hasPowerType(powerType));
    }

    @Override
    public <T extends Power> T getPower(PowerType<T> powerType) {
        if(powers.containsKey(powerType)) {
            return (T)powers.get(powerType);
        }
        return null;
    }

    @Override
    public List<Power> getPowers() {
        List<Power> list = new LinkedList<>();
        list.addAll(powers.values());
        return list;
    }

    private Set<PowerType<?>> getPowerTypes() {
        Set<PowerType<?>> powerTypes = new HashSet<>();
        origins.values().forEach(origin -> {
            if(origin != null) {
                origin.getPowerTypes().forEach(powerTypes::add);
            }
        });
        return powerTypes;
    }

    @Override
    public <T extends Power> List<T> getPowers(Class<T> powerClass) {
        return getPowers(powerClass, false);
    }

    @Override
    public <T extends Power> List<T> getPowers(Class<T> powerClass, boolean includeInactive) {
        List<T> list = new LinkedList<>();
        for(Power power : powers.values()) {
            if(powerClass.isAssignableFrom(power.getClass()) && (includeInactive || power.isActive())) {
                list.add((T)power);
            }
        }
        return list;
    }

    @Override
    public void setOrigin(OriginLayer layer, Origin origin) {
        Origin oldOrigin = getOrigin(layer);
        if(oldOrigin == origin) {
            return;
        }
        this.origins.put(layer, origin);
        if(oldOrigin != null) {
            List<PowerType<?>> powersToRemove = new LinkedList<>();
            for (Map.Entry<PowerType<?>, Power> powerEntry: powers.entrySet()) {
                if(!hasPowerType(powerEntry.getKey())) {
                    powerEntry.getValue().onRemoved();
                    powersToRemove.add(powerEntry.getKey());
                }
            }
            for(PowerType<?> toRemove : powersToRemove) {
                powers.remove(toRemove);
            }
        }
        origin.getPowerTypes().forEach(powerType -> {
            if(!powers.containsKey(powerType)) {
                Power power = powerType.create(player);
                this.powers.put(powerType, power);
                power.onAdded();
            }
        });
        if(this.hasAllOrigins()) {
            this.hadOriginBefore = true;
        }
    }

    @Override
    public void fromTag(CompoundTag compoundTag) {
        this.fromTag(compoundTag, true);
    }

    private void fromTag(CompoundTag compoundTag, boolean callPowerOnAdd) {
        if(player == null) {
            Origins.LOGGER.error("Player was null in `fromTag`! This is a bug!");
        }
        if(this.origins != null) {
            if(callPowerOnAdd) {
                for (Power power: powers.values()) {
                    power.onRemoved();
                }
            }
            powers.clear();
        }

        this.origins.clear();

        if(compoundTag.contains("Origin")) {
            try {
                OriginLayer defaultOriginLayer = OriginLayers.getLayer(new Identifier(Origins.MODID, "origin"));
                this.origins.put(defaultOriginLayer, OriginRegistry.get(Identifier.tryParse(compoundTag.getString("Origin"))));
            } catch(IllegalArgumentException e) {
                Origins.LOGGER.warn("Player " + player.getDisplayName().asString() + " had old origin which could not be migrated: " + compoundTag.getString("Origin"));
            }
        } else {
            ListTag originLayerList = (ListTag)compoundTag.get("OriginLayers");
            if(originLayerList != null) {
                for(int i = 0; i < originLayerList.size(); i++) {
                    CompoundTag layerTag = originLayerList.getCompound(i);
                    Identifier layerId = Identifier.tryParse(layerTag.getString("Layer"));
                    OriginLayer layer = null;
                    try {
                        layer = OriginLayers.getLayer(layerId);
                    } catch(IllegalArgumentException e) {
                        Origins.LOGGER.warn("Could not find origin layer with id " + layerId.toString() + ", which existed on the data of player " + player.getDisplayName().asString() + ".");
                    }
                    if(layer != null) {
                        Identifier originId = Identifier.tryParse(layerTag.getString("Origin"));
                        Origin origin = null;
                        try {
                            origin = OriginRegistry.get(originId);
                        } catch(IllegalArgumentException e) {
                            Origins.LOGGER.warn("Could not find origin with id " + originId.toString() + ", which existed on the data of player " + player.getDisplayName().asString() + ".");
                        }
                        if(origin != null) {
                            if(!layer.contains(origin)) {
                                Origins.LOGGER.warn("Origin with id " + origin.getIdentifier().toString() + " is not in layer " + layer.getIdentifier().toString() + ", but was found on " + player.getDisplayName().asString() + ", setting to EMPTY.");
                                origin = Origin.EMPTY;
                            }
                            this.origins.put(layer, origin);
                        }
                    }
                }
            }
        }
        this.hadOriginBefore = compoundTag.getBoolean("HadOriginBefore");
        ListTag powerList = (ListTag)compoundTag.get("Powers");
        for(int i = 0; i < powerList.size(); i++) {
            CompoundTag powerTag = powerList.getCompound(i);
            PowerType<?> type = ModRegistries.POWER_TYPE.get(Identifier.tryParse(powerTag.getString("Type")));
            if(hasPowerType(type)) {
                Tag data = powerTag.get("Data");
                Power power = type.create(player);
                power.fromTag(data);
                this.powers.put(type, power);
                if(callPowerOnAdd) {
                    power.onAdded();
                }
            }
        }
        this.getPowerTypes().forEach(pt -> {
            if(!this.powers.containsKey(pt)) {
                Power power = pt.create(player);
                this.powers.put(pt, power);
            }
        });
    }

    @Override
    public CompoundTag toTag(CompoundTag compoundTag) {
        ListTag originLayerList = new ListTag();
        for(Map.Entry<OriginLayer, Origin> entry : origins.entrySet()) {
            CompoundTag layerTag = new CompoundTag();
            layerTag.putString("Layer", entry.getKey().getIdentifier().toString());
            layerTag.putString("Origin", entry.getValue().getIdentifier().toString());
            originLayerList.add(layerTag);
        }
        compoundTag.put("OriginLayers", originLayerList);
        compoundTag.putBoolean("HadOriginBefore", this.hadOriginBefore);
        ListTag powerList = new ListTag();
        for(Map.Entry<PowerType<?>, Power> powerEntry : powers.entrySet()) {
            CompoundTag powerTag = new CompoundTag();
            powerTag.putString("Type", ModRegistries.POWER_TYPE.getId(powerEntry.getKey()).toString());
            powerTag.put("Data", powerEntry.getValue().toTag());
            powerList.add(powerTag);
        }
        compoundTag.put("Powers", powerList);
        return compoundTag;
    }

    @Override
    public void readFromPacket(PacketByteBuf buf) {
        CompoundTag compoundTag = buf.readCompoundTag();
        if(compoundTag != null) {
            this.fromTag(compoundTag, false);
        }
    }

    @Override
    public Entity getEntity() {
        return this.player;
    }

    @Override
    public ComponentType<?> getComponentType() {
        return ModComponents.ORIGIN;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder("OriginComponent[\n");
        for (Map.Entry<PowerType<?>, Power> powerEntry : powers.entrySet()) {
            str.append("\t").append(ModRegistries.POWER_TYPE.getId(powerEntry.getKey())).append(": ").append(powerEntry.getValue().toTag().toString()).append("\n");
        }
        str.append("]");
        return str.toString();
    }
}
