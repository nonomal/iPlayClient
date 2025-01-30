package top.ourfor.app.iplayx.store;


import static top.ourfor.app.iplayx.module.Bean.XGET;

import android.app.Application;
import android.widget.Toast;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.With;
import lombok.val;
import top.ourfor.app.iplayx.R;
import top.ourfor.app.iplayx.action.DispatchAction;
import top.ourfor.app.iplayx.action.DriveUpdateAction;
import top.ourfor.app.iplayx.action.SiteListUpdateAction;
import top.ourfor.app.iplayx.action.SiteUpdateAction;
import top.ourfor.app.iplayx.api.emby.EmbyApi;
import top.ourfor.app.iplayx.api.emby.EmbyModel;
import top.ourfor.app.iplayx.api.iplay.iPlayApi;
import top.ourfor.app.iplayx.api.iplay.iPlayModel;
import top.ourfor.app.iplayx.api.jellyfin.JellyfinApi;
import top.ourfor.app.iplayx.bean.JSONAdapter;
import top.ourfor.app.iplayx.bean.KVStorage;
import top.ourfor.app.iplayx.common.api.EmbyLikeApi;
import top.ourfor.app.iplayx.common.type.MediaPlayState;
import top.ourfor.app.iplayx.common.type.MediaType;
import top.ourfor.app.iplayx.common.type.ServerType;
import top.ourfor.app.iplayx.config.AppSetting;
import top.ourfor.app.iplayx.model.AlbumModel;
import top.ourfor.app.iplayx.model.ImageModel;
import top.ourfor.app.iplayx.model.MediaModel;
import top.ourfor.app.iplayx.model.SiteModel;
import top.ourfor.app.iplayx.model.drive.Drive;
import top.ourfor.app.iplayx.view.video.PlayerSourceModel;

@Data
@EqualsAndHashCode
@Builder
@With
@ToString
@AllArgsConstructor
public class GlobalStore {
    @JsonIgnoreProperties
    private static String storeKey = "@store/emby";

    @JsonIgnore
    private EmbyLikeApi api;

    @JsonProperty("site")
    private SiteModel site;
    @JsonProperty("sites")
    private List<SiteModel> sites;

    @JsonProperty("drive")
    private Drive drive;
    @JsonProperty("drives")
    private List<Drive> drives;

    @JsonProperty("dataSource")
    private DataSource dataSource;

    public static GlobalStore shared = defaultStore();

    public GlobalStore() {
        drives = new ArrayList<>();
        sites = new ArrayList<>();
        dataSource = createDataSource();
    }

    public static GlobalStore defaultStore() {
        KVStorage kv = XGET(KVStorage.class);
        GlobalStore instance = kv.getObject(storeKey, GlobalStore.class);
        if (instance == null) {
            instance = new GlobalStore();
            kv.setObject(storeKey, instance);
        } else {
            val serverType = instance.site != null ? instance.site.getServerType() : ServerType.None;
            if (serverType == ServerType.Emby) {
                instance.api = EmbyApi.builder()
                        .site(instance.site)
                        .build();
            } else if (serverType == ServerType.Jellyfin) {
                instance.api = JellyfinApi.builder()
                        .site(instance.site)
                        .build();
            } else if (serverType == ServerType.iPlay) {
                instance.api = iPlayApi.builder()
                        .site(instance.site)
                        .build();
            } else {
                instance.api = EmbyApi.builder()
                        .site(instance.site)
                        .build();
            }
            if (instance.dataSource == null) {
                instance.dataSource = createDataSource();
            }
            instance.drives.removeIf(Objects::isNull);
        }
        return instance;
    }

    public void addNewSite(SiteModel site) {
        this.site = site;
        val serverType = site.getServerType();
        if (serverType == ServerType.Emby) {
            api = EmbyApi.builder()
                    .site(site)
                    .build();
        } else if (serverType == ServerType.Jellyfin) {
             api = JellyfinApi.builder()
                     .site(site)
                     .build();
        } else if (serverType == ServerType.iPlay) {
            api = iPlayApi.builder()
                    .site(site)
                    .build();
        }
        if (dataSource == null) {
            dataSource = createDataSource();
        }
        boolean has = false;
        for (int i = 0; i < sites.size(); i++) {
            val oldSite = sites.get(i);
            if (oldSite == null) {
                sites.remove(i);
                i--;
                continue;
            }

            if (oldSite.getEndpoint().getBaseUrl().equals(site.getEndpoint().getBaseUrl()) &&
                oldSite.getUserName().equals(site.getUserName())) {
                sites.set(i, site);
                has = true;
                break;
            }
        }
        if (!has) {
            sites.add(site);
        }
        save();
        val action = XGET(SiteListUpdateAction.class);
        if (action == null) return;
        XGET(DispatchAction.class).runOnUiThread(() -> action.updateSiteList());
    }

    private static DataSource createDataSource() {
        return DataSource.builder()
                .albums(new CopyOnWriteArrayList<>())
                .resume(new CopyOnWriteArrayList<>())
                .albumMedias(new ConcurrentHashMap<>())
                .mediaMap(new ConcurrentHashMap<>())
                .seasonEpisodes(new ConcurrentHashMap<>())
                .seriesSeasons(new ConcurrentHashMap<>())
                .build();
    }


    public void save() {
        KVStorage kv = XGET(KVStorage.class);
        kv.setObject(storeKey, this);
    }

    public String toJSON() {
        return XGET(JSONAdapter.class).toJSON(this);
    }

    public String toSiteJSON() {
        val store = this.withDataSource(null);
        return XGET(JSONAdapter.class).toJSON(store);
    }

    public String toSiteJSON(boolean filterSync) {
        val store = this.withDataSource(null);
        if (filterSync) {
            store.sites.removeIf(site -> !site.isSync());
        }
        return XGET(JSONAdapter.class).toJSON(store);
    }

    public void fromJSON(String json) {
        val store = XGET(JSONAdapter.class).fromJSON(json, GlobalStore.class);
        if (store == null) return;
        this.site = store.site;
        this.sites = store.sites;
        this.dataSource = store.dataSource;
    }

    public void fromSiteJSON(String json) {
        val store = XGET(JSONAdapter.class).fromJSON(json, GlobalStore.class);
        if (store == null) return;
        this.site = store.site;
        this.sites = store.sites;
        switchSite(store.site);
        this.dataSource = createDataSource();
    }

    public void getAlbums(Consumer<List<AlbumModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getAlbums(response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof EmbyModel.EmbyPageableModel<?>) {
                List<EmbyModel.EmbyAlbumModel> items = ((EmbyModel.EmbyPageableModel<EmbyModel.EmbyAlbumModel>) response).getItems();
                if (dataSource.getAlbums() == null) {
                    dataSource.setAlbums(new CopyOnWriteArrayList<>());
                }
                dataSource.getAlbums().clear();
                val albumItems = items.stream().map(item -> AlbumModel.builder()
                        .id(item.getId())
                        .title(item.getName())
                        .type(item.getCollectionType())
                        .backdrop(item.getImage().getPrimary())
                        .build()).collect(Collectors.toList());
                dataSource.getAlbums().addAll(albumItems);
                completion.accept(albumItems);
            } else if (response instanceof List<?>){
                val items = (List<iPlayModel.AlbumModel>) response;
                if (dataSource.getAlbums() == null) {
                    dataSource.setAlbums(new CopyOnWriteArrayList<>());
                }
                dataSource.getAlbums().clear();
                val albumItems = items.stream().map(item -> AlbumModel.builder()
                        .id(item.getId())
                        .title(item.getName())
                        .type("movies")
                        .backdrop(item.image.getBackdrop())
                        .build()).collect(Collectors.toList());
                dataSource.getAlbums().addAll(albumItems);
                completion.accept(albumItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void markFavorite(String id, boolean isFavorite, Consumer<EmbyModel.EmbyUserData> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.markFavorite(id, isFavorite, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof EmbyModel.EmbyUserData) {
                EmbyModel.EmbyUserData data = (EmbyModel.EmbyUserData) response;
                completion.accept(data);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getResume(Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getResume(response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                if (dataSource.getResume() == null) {
                    dataSource.setResume(new CopyOnWriteArrayList<>());
                }
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                dataSource.getResume().clear();
                dataSource.getResume().addAll(mediaItems);
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getAlbumLatestMedias(String id, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getAlbumLatestMedias(id, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                if (dataSource.albumMedias == null) {
                    dataSource.albumMedias = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                dataSource.getAlbumMedias().put(id, new CopyOnWriteArrayList<>(mediaItems));
                save();
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getAllFavoriteMedias(MediaType type, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        val typeName = switch (type) {
            case Series -> "Series,Season";
            case Movie -> "Movie";
            case Episode -> "Episode";
            default -> "";
        };
        val query = Map.of(
                "Filters", "IsFavorite",
                "Limit", "50",
                "IncludeItemTypes", typeName,
                "StartIndex", "0",
                "SortBy", "SortName"
        );
        api.getAllMedias(query, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                if (dataSource.albumMedias == null) {
                    dataSource.albumMedias = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getFavoriteMedias(MediaType type, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        val typeName = switch (type) {
            case Series -> "Series,Season";
            case Movie -> "Movie";
            case Episode -> "Episode";
            default -> "";
        };
        val query = Map.of(
                "Filters", "IsFavorite",
                "Limit", "50",
                "IncludeItemTypes", typeName,
                "StartIndex", "0",
                "SortBy", "SortName"
        );
        api.getMedias(query, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                if (dataSource.albumMedias == null) {
                    dataSource.albumMedias = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getSeasons(String seriesId, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getSeasons(seriesId, seasons -> {
            if (seasons == null) {
                completion.accept(null);
                return;
            }
            if (seasons instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) seasons;
                if (dataSource.seriesSeasons == null) {
                    dataSource.seriesSeasons = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                dataSource.seriesSeasons.put(seriesId, new CopyOnWriteArrayList<>(mediaItems));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getEpisodes(String seriesId, String seasonId, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getEpisodes(seriesId, seasonId, episodes -> {
            if (episodes == null) {
                completion.accept(null);
                return;
            }
            if (episodes instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) episodes;
                if (dataSource.seasonEpisodes == null) {
                    dataSource.seasonEpisodes = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                dataSource.seasonEpisodes.put(seasonId, new CopyOnWriteArrayList<>(mediaItems));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getPlayback(String id, Consumer<EmbyModel.EmbyPlaybackModel> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getPlayback(id, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof EmbyModel.EmbyPlaybackModel) {
                EmbyModel.EmbyPlaybackModel data = (EmbyModel.EmbyPlaybackModel) response;
                completion.accept(data);
            } else {
                completion.accept(null);
            }
        });
    }

    public List<PlayerSourceModel> getPlaySources(MediaModel media, EmbyModel.EmbyPlaybackModel playback) {
        List<PlayerSourceModel> sources = new ArrayList<>(5);
        sources.add(PlayerSourceModel.builder()
                .name(media.getName())
                .value(media.getName())
                .type(PlayerSourceModel.PlayerSourceType.Title)
                .build());

        boolean isEpisode = media.getType().equals("Episode");
        boolean isMusic = media.getType().equals("Audio") || media.getType().equals("MusicAlbum");
        sources.add(PlayerSourceModel.builder()
                .value(isEpisode || isMusic ? media.getImage().getPrimary() : media.getImage().getBackdrop())
                .type(PlayerSourceModel.PlayerSourceType.PosterImage)
                .build());

        if (isEpisode) {
            val series = dataSource.mediaMap.get(media.getSeriesId());
            if (series != null) {
                sources.add(PlayerSourceModel.builder()
                        .value(series.getImage().getLogo())
                        .type(PlayerSourceModel.PlayerSourceType.LogoImage)
                        .build());
            }
        } else {
            sources.add(PlayerSourceModel.builder()
                    .value(media.getImage().getLogo())
                    .type(PlayerSourceModel.PlayerSourceType.LogoImage)
                    .build());
        }

        if (playback.getMediaSources() != null) {
            playback.getMediaSources().forEach(source -> {
                if (source.getDirectStreamUrl() != null) {
                    sources.add(PlayerSourceModel.builder()
                            .id(source.getId())
                            .name(source.getName())
                            .type(PlayerSourceModel.PlayerSourceType.Video)
                            .url(source.getDirectStreamUrl())
                            .build());
                }
                source.getMediaStreams().forEach(stream -> {
                    PlayerSourceModel.PlayerSourceType type = PlayerSourceModel.PlayerSourceType.None;
                    if (stream.getType().equals("Audio")) {
                        type = PlayerSourceModel.PlayerSourceType.Audio;
                    } else if (stream.getType().equals("Subtitle")) {
                        type = PlayerSourceModel.PlayerSourceType.Subtitle;
                    }
                    if (type == PlayerSourceModel.PlayerSourceType.Subtitle &&
                        stream.getIsExternal() &&
                        stream.getDeliveryUrl() != null) {
                        sources.add(PlayerSourceModel.builder()
                                .name(stream.getDisplayTitle())
                                .value(stream.getDisplayLanguage())
                                .type(type)
                                .url(stream.getDeliveryUrl())
                                .build());
                    }

                });
            });
        }
        return sources;
    }

    public String getPlayUrl(EmbyModel.EmbyPlaybackModel playback) {
        val mediaSources = playback.getMediaSources();
        for (val source : mediaSources) {
            if (AppSetting.shared.isUseStrmFirst() &&
                    source.getPath().startsWith("http")) {
                return source.getPath();
            }

            if (source.getDirectStreamUrl() != null) {
                return source.getDirectStreamUrl();
            }
        }
        return null;
    }

    public void getAlbumAllMedias(String id, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        val query = Map.of(
                "Limit", "1",
                "ParentId", id,
                "Recursive", "true",
                "IncludeItemTypes", "Series,Movie",
                "StartIndex", "0",
                "SortBy", "SortName"
        );

        CopyOnWriteArrayList<EmbyModel.EmbyMediaModel> items = new CopyOnWriteArrayList<>();
        api.getMediasCount(query, count -> {
            CountDownLatch latch = new CountDownLatch((int)Math.ceil(count / 100.0));
            for (int i = 0; i < count; i+=100) {
                int start = i;
                XGET(ThreadPoolExecutor.class).submit(() -> {
                    getAlbumMedias(id, start, medias -> {
                        items.addAll(medias);
                        latch.countDown();
                    });
                });
            }
            try {
                latch.await();
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                completion.accept(mediaItems);
                return;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            completion.accept(null);
        });
    }

    public void getAlbumMedias(String id, Consumer<List<EmbyModel.EmbyMediaModel>> completion) {
        getAlbumMedias(id, 0, completion);
    }

    public void getAlbumMedias(String id, int start, Consumer<List<EmbyModel.EmbyMediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        val query = Map.of(
                "Limit", "100",
                "ParentId", id,
                "Recursive", "true",
                "IncludeItemTypes", "Series,Movie",
                "StartIndex", String.valueOf(start),
                "SortBy", "DateCreated,SortName",
                "SortOrder", "Descending"
        );
        api.getMedias(query, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                if (dataSource.albumMedias == null) {
                    dataSource.albumMedias = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                dataSource.albumMedias.put(id, new CopyOnWriteArrayList<>(mediaItems));
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                completion.accept(items);
            } else {
                completion.accept(null);
            }
        });
    }

    public void getItems(Map<String, String> query, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }

        api.getMedias(query, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }


    public void trackPlay(MediaPlayState state, EmbyModel.EmbyPlaybackData data) {
        if (api == null) {
            return;
        }
        api.trackPlay(state, data, r -> {

        });
    }

    public void addDrive(Drive drive) {
        if (drives == null) {
            drives = new ArrayList<>();
        }
        drives.add(drive);
        save();
        val action = XGET(DriveUpdateAction.class);
        if (action == null) return;
        action.onDriveAdded(drive);
    }

    public void switchDrive(Drive drive) {
        this.drive = drive;
        save();
        val action = XGET(DriveUpdateAction.class);
        if (action == null) return;
        action.onSelectedDriveChanged(drive);
    }

    public void switchSite(SiteModel site) {
        this.site = site;
        val serverType = site.getServerType();
        if (serverType == ServerType.Emby) {
            api = EmbyApi.builder()
                    .site(site)
                    .build();
        } else if (serverType == ServerType.Jellyfin) {
            api = JellyfinApi.builder()
                    .site(site)
                    .build();
        } else {
            api = EmbyApi.builder()
                    .site(site)
                    .build();
        }
        dataSource = createDataSource();
        save();
        val action = XGET(SiteUpdateAction.class);
        if (action == null) return;
        action.onSiteUpdate();
    }

    public void search(String keyword, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        val query = Map.of(
                "SearchTerm", keyword,
                "Limit", "100",
                "IncludeItemTypes", "Series,Movie,Episode",
                "StartIndex", "0",
                "SortBy", "SortName",
                "GroupProgramsBySeries", "true"
        );
        api.getMedias(query, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                if (dataSource.albumMedias == null) {
                    dataSource.albumMedias = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                mediaItems.forEach(item -> dataSource.getMediaMap().put(item.getId(), item));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }

    public void removeSite(SiteModel model) {
        if (site.equals(model)) {
            XGET(DispatchAction.class).runOnUiThread(() -> {
                val context = XGET(Application.class);
                Toast.makeText(context, context.getString(R.string.can_remove_current_site), Toast.LENGTH_SHORT).show();
            });
            return;
        }
        sites.removeIf(site -> site.equals(model));
        save();
        val action = XGET(SiteListUpdateAction.class);
        if (action == null) return;
        XGET(DispatchAction.class).runOnUiThread(() -> action.updateSiteList());
    }

    public void removeDrive(Drive model) {
        if (drive.equals(model)) {
            XGET(DispatchAction.class).runOnUiThread(() -> {
                val context = XGET(Application.class);
                Toast.makeText(context, context.getString(R.string.can_remove_current_drive), Toast.LENGTH_SHORT).show();
            });
            return;
        }
        drives.removeIf(drive -> drive.equals(model));
        save();
        val action = XGET(DriveUpdateAction.class);
        if (action == null) return;
        action.onDriveRemoved(model);
    }

    public boolean hasValidSite() {
        return site != null && api != null;
    }

    public boolean hasValidDrive() {
        return drive != null && drives != null && !drives.isEmpty();
    }

    public void searchSuggestion(Consumer<List<MediaModel>> prompts) {
        if (api == null) {
            prompts.accept(null);
            return;
        }
        api.getRecommendations(result -> {
            if (result == null) {
                prompts.accept(null);
                return;
            }
            if (result instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) result;
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                prompts.accept(mediaItems);
            } else {
                prompts.accept(null);
            }
        });
    }

    public String getSiteName() {
        if (site != null) {
            return site.getRemark();
        }
        return null;
    }

    public void getSimilar(String id, Consumer<List<MediaModel>> completion) {
        if (api == null) {
            completion.accept(null);
            return;
        }
        api.getSimilar(id, response -> {
            if (response == null) {
                completion.accept(null);
                return;
            }
            if (response instanceof List<?>) {
                List<EmbyModel.EmbyMediaModel> items = (List<EmbyModel.EmbyMediaModel>) response;
                if (dataSource.mediaMap == null) {
                    dataSource.mediaMap = new ConcurrentHashMap<>();
                }
                val mediaItems = items.stream().map(EmbyModel.EmbyMediaModel::toMediaModel).collect(Collectors.toList());
                dataSource.seasonEpisodes.put(id, new CopyOnWriteArrayList<>(mediaItems));
                completion.accept(mediaItems);
            } else {
                completion.accept(null);
            }
        });
    }
}
