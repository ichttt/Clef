package me.ichun.mods.clef.common.core;

import me.ichun.mods.clef.common.Clef;
import me.ichun.mods.clef.common.item.ItemInstrument;
import me.ichun.mods.clef.common.packet.PacketPlayingTracks;
import me.ichun.mods.clef.common.tileentity.TileEntityInstrumentPlayer;
import me.ichun.mods.clef.common.util.abc.AbcLibrary;
import me.ichun.mods.clef.common.util.abc.TrackFile;
import me.ichun.mods.clef.common.util.abc.play.Track;
import me.ichun.mods.clef.common.util.instrument.Instrument;
import me.ichun.mods.clef.common.util.instrument.InstrumentLibrary;
import me.ichun.mods.ichunutil.common.iChunUtil;
import me.ichun.mods.ichunutil.common.util.IOUtil;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.Hand;
import net.minecraft.world.storage.loot.*;
import net.minecraft.world.storage.loot.conditions.ILootCondition;
import net.minecraftforge.event.LootTableLoadEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import org.apache.commons.lang3.RandomStringUtils;

import java.util.HashSet;
import java.util.Iterator;

public class EventHandlerServer
{
    public HashSet<Track> tracksPlaying = new HashSet<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if(event.side.isServer() && event.phase == TickEvent.Phase.END)
        {
            if(iChunUtil.eventHandlerServer.ticks + 5 % 10 == 2)
            {
                ItemStack isMain = event.player.getHeldItemMainhand();
                ItemStack isOff = event.player.getHeldItemOffhand();
                if(isMain.getItem() == Clef.Items.INSTRUMENT.get())
                {
                    InstrumentLibrary.checkForInstrument(isMain, (ServerPlayerEntity)event.player);
                }
                if(isOff.getItem() == Clef.Items.INSTRUMENT.get())
                {
                    InstrumentLibrary.checkForInstrument(isOff, (ServerPlayerEntity)event.player);
                }
            }
        }
    }

    @SubscribeEvent
    public void onItemDrop(LivingDropsEvent event)
    {
        if(!event.getEntityLiving().getEntityWorld().isRemote && event.getEntityLiving() instanceof ServerPlayerEntity)
        {
            for(ItemEntity item : event.getDrops())
            {
                if(item.getItem().getItem() == Clef.Items.INSTRUMENT.get())
                {
                    CompoundNBT tag = item.getItem().getTag();
                    if(tag != null)
                    {
                        String instName = tag.getString("itemName");
                        Instrument is = InstrumentLibrary.getInstrumentByName(instName);
                        if(is == null) //request the item then?
                        {
                            InstrumentLibrary.requestInstrument(instName, (ServerPlayerEntity)event.getEntityLiving());
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDropsEvent event)
    {
        if(!event.getEntityLiving().getEntityWorld().isRemote && (!Clef.configCommon.onlyHostileMobSpawn || event.getEntityLiving() instanceof IMob) && event.getEntityLiving().getRNG().nextFloat() < (Clef.configCommon.mobDropRate / 10000F) * (event.getLootingLevel() + 1))
        {
            ItemStack stack = new ItemStack(Clef.Items.INSTRUMENT.get());
            InstrumentLibrary.assignRandomInstrument(stack);
            event.getDrops().add(event.getEntityLiving().entityDropItem(stack, 0F));
        }
    }

    @SubscribeEvent
    public void onLivingSpawn(LivingSpawnEvent.SpecialSpawn event)
    {
        if(!event.getEntityLiving().getEntityWorld().isRemote && event.getEntityLiving() instanceof ZombieEntity && event.getEntityLiving().getRNG().nextFloat() < (Clef.configCommon.zombieSpawnRate / 10000F))
        {
            ZombieEntity zombie = (ZombieEntity)event.getEntityLiving();
            if(zombie.getHeldItemMainhand().isEmpty())
            {
                ItemStack stack = new ItemStack(Clef.Items.INSTRUMENT.get());
                InstrumentLibrary.assignRandomInstrument(stack);
                zombie.setHeldItem(Hand.MAIN_HAND, stack);
            }
        }
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent event)
    {
        if(Clef.configCommon.zombiesCanUseInstruments && !event.getEntityLiving().world.isRemote && event.getEntityLiving() instanceof ZombieEntity)
        {
            ZombieEntity zombie = (ZombieEntity)event.getEntityLiving();
            if(zombie.getRNG().nextFloat() < 0.004F && !ItemInstrument.getUsableInstrument(zombie).isEmpty() && getTrackPlayedByPlayer(zombie) == null)
            {
                Track track = Clef.eventHandlerServer.findTrackByBand("zombies");
                if(track != null)
                {
                    if(track.zombies.add(zombie.getEntityId()))
                    {
                        Clef.channel.sendTo(new PacketPlayingTracks(track), PacketDistributor.ALL.noArg());
                    }
                }
                else
                {
                    TrackFile randTrack = AbcLibrary.tracks.get(zombie.getRNG().nextInt(AbcLibrary.tracks.size()));
                    track = new Track(RandomStringUtils.randomAscii(IOUtil.IDENTIFIER_LENGTH), "zombies", randTrack.md5, randTrack.track, false);
                    if(track.getTrack().trackLength > 0)
                    {
                        track.playAtProgress(zombie.getRNG().nextInt(track.getTrack().trackLength));
                    }
                    Clef.eventHandlerServer.tracksPlaying.add(track);
                    track.zombies.add(zombie.getEntityId());
                    Clef.channel.sendTo(new PacketPlayingTracks(track), PacketDistributor.ALL.noArg());
                }
            }
        }
    }

    @SubscribeEvent
    public void onLootTableEvent(LootTableLoadEvent event)
    {
        if(Clef.configCommon.lootSpawnRate > 0)
        {
            for(String s : Clef.configCommon.disabledLootChests)
            {
                if(event.getName().toString().equals(s))
                {
                    return;
                }
            }
            if(event.getName().getPath().contains("chest"))
            {
                event.getTable().addPool(LootPool.builder()
                        .addEntry(ItemLootEntry.builder(Clef.Items.INSTRUMENT.get()).weight(Clef.configCommon.lootSpawnRate).acceptFunction(() -> new LootFunction(new ILootCondition[0]) {
                            @Override
                            protected ItemStack doApply(ItemStack stack, LootContext context)
                            {
                                InstrumentLibrary.assignRandomInstrument(stack);
                                return stack;
                            }
                        }))
                        .name("clef_instrument")
                        .build());
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if(event.phase == TickEvent.Phase.END)
        {
            Iterator<Track> ite = tracksPlaying.iterator();
            while(ite.hasNext())
            {
                Track track = ite.next();
                if(!track.update())
                {
                    ite.remove();
                    continue;
                }
            }
        }
    }

    public void stopPlayingTrack(ServerPlayerEntity player, String trackId)
    {
        for(Track track : tracksPlaying)
        {
            if(track.getId().equals(trackId))
            {
                track.players.remove(player);
                if(!track.hasObjectsPlaying())
                {
                    track.stop();
                }
                Clef.channel.sendTo(new PacketPlayingTracks(track), PacketDistributor.ALL.noArg());
                break;
            }
        }
    }

    public Track getTrackPlayedByPlayer(ZombieEntity zombie)
    {
        for(Track track : tracksPlaying)
        {
            if(track.zombies.contains(zombie.getEntityId()))
            {
                return track;
            }
        }
        return null;
    }

    public Track getTrackPlayedByPlayer(TileEntityInstrumentPlayer player)
    {
        for(Track track : tracksPlaying)
        {
            if(track.instrumentPlayers.containsKey(player.getWorld().getDimension().getType().getRegistryName()) && track.instrumentPlayers.get(player.getWorld().getDimension().getType().getRegistryName()).contains(player.getPos()))
            {
                return track;
            }
        }
        return null;
    }

    public Track getTrackPlayedByPlayer(PlayerEntity player)
    {
        for(Track track : tracksPlaying)
        {
            if(track.players.containsKey(player))
            {
                return track;
            }
        }
        return null;
    }

    public Track findTrackByBand(String bandName)
    {
        for(Track track : tracksPlaying)
        {
            if(track.getBandName().equalsIgnoreCase(bandName))
            {
                return track;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onPlayerConnect(PlayerEvent.PlayerLoggedInEvent event)
    {
        HashSet<Track> tracks = new HashSet<>();
        for(Track track : tracksPlaying)
        {
            if(track.getTrack() != null) //this means the track is actively played
            {
                tracks.add(track);
            }
        }
        AbcLibrary.startPlayingTrack((ServerPlayerEntity)event.getPlayer(), tracks.toArray(new Track[tracks.size()]));
    }

    @SubscribeEvent
    public void onServerStoppingEvent(FMLServerStoppingEvent event)
    {
        shutdownServer();
    }

    public void shutdownServer()
    {
        tracksPlaying.clear();

        AbcLibrary.tracksWaitingForTrackInfo.clear();
        AbcLibrary.requestedABCFromPlayers.clear();
        InstrumentLibrary.requestsFromPlayers.clear();
        InstrumentLibrary.requestedInstrumentsFromPlayers.clear();
    }
}
