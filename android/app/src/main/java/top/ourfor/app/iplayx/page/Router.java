package top.ourfor.app.iplayx.page;

import static top.ourfor.app.iplayx.module.Bean.XGET;
import static top.ourfor.app.iplayx.page.PageMaker.makePage;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.view.animation.Interpolator;

import androidx.appcompat.app.ActionBar;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import top.ourfor.app.iplayx.R;
import top.ourfor.app.iplayx.action.NavigationTitleBar;
import top.ourfor.app.iplayx.bean.Navigator;
import top.ourfor.app.iplayx.bean.PageLifecycle;
import top.ourfor.app.iplayx.common.annotation.ViewController;
import top.ourfor.app.iplayx.store.GlobalStore;
import top.ourfor.app.iplayx.util.AnnotationUtil;
import top.ourfor.app.iplayx.util.LayoutUtil;
import top.ourfor.app.iplayx.view.infra.Toolbar;

@Slf4j
public class Router implements Navigator {
    private static final HashMap<Integer, PageType> pageType;
    private static Map<Integer, Stack<Page>> navigators;
    private static Integer stackId = null;
    private static final Map<Page, Integer> pageId = new HashMap<>();
    private static final Map<String, Class<Page>> pageMap = new HashMap<>();

    ViewGroup container;
    BottomNavigationView bottomNavigation;
    Toolbar toolbar;
    Animation pushAnimation;
    Animation popAnimation;

    static {
        pageType = new HashMap<>();
        pageType.put(R.id.homePage, PageType.HOME);
        pageType.put(R.id.messagePage, PageType.MESSAGE);
        pageType.put(R.id.filePage, PageType.FILE);
        pageType.put(R.id.starPage, PageType.STAR);
        pageType.put(R.id.settingPage, PageType.SETTING);
        pageType.put(R.id.searchPage, PageType.SEARCH);
        pageType.put(R.id.loginPage, PageType.LOGIN);
        pageType.put(R.id.themePage, PageType.THEME);
        pageType.put(R.id.videoPage, PageType.VIDEO_CONFIG);
        pageType.put(R.id.audioPage, PageType.AUDIO_CONFIG);
        pageType.put(R.id.playerPage, PageType.PLAYER);
        pageType.put(R.id.musicPage, PageType.MUSIC);
        pageType.put(R.id.cachePage, PageType.CACHE);
        pageType.put(R.id.picturePage, PageType.IMAGE_CONFIG);
        pageType.put(R.id.musicPlayerPage, PageType.MUSIC_PLAYER);
        pageType.put(R.id.webPage, PageType.WEB);
    }

    Router(ViewGroup container, BottomNavigationView bottomNavigation, Toolbar toolbar) {
        this.container = container;
        this.bottomNavigation = bottomNavigation;
        this.toolbar = toolbar;
        pushAnimation = AnimationUtils.loadAnimation(container.getContext(), R.anim.slide_in);
        popAnimation = AnimationUtils.loadAnimation(container.getContext(), R.anim.slide_out);
        navigators = new HashMap<>();
        bottomNavigation.setOnItemSelectedListener(item -> {
            navigate(item.getItemId());
            return true;
        });
    }

    public void scanPage() {
        try {
            var app = XGET(Application.class);
            assert app != null;
            String packageName = app.getPackageName();
            val basePackageName = packageName.replace(".debug", "") + ".page";
            AnnotationUtil.load(basePackageName, app.getPackageCodePath());
            List<Class<?>> vcs = AnnotationUtil.findDecorateWith(ViewController.class);
            for (val vc : vcs) {
                val annotation = vc.getAnnotation(ViewController.class);
                if (Page.class.isAssignableFrom(vc)) {
                    assert annotation != null;
                    pageMap.put(annotation.name(), (Class<Page>) vc);
                }
            }
            log.info("view controllers: {}", pageMap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    void navigate(int id) {
        var pages = navigators.computeIfAbsent(id, k -> new Stack<>());
        if (pages.isEmpty()) {
            val page = makePage(id);
            page.create(container.getContext(), new HashMap<>());
            if (page.view() instanceof PageLifecycle lifecycle) {
                lifecycle.onAttach();
            }
            pageId.put(page, id);
            pages.push(page);
        }
        val page = pages.peek();
        container.removeAllViews();
        val view = page.view();
        page.viewWillAppear();
        container.addView(view, LayoutUtil.fill());
        onNavigateChange(id);
        page.viewDidAppear();
        stackId = id;
    }

    @Override
    public void pushPage(int id, Map<String, Object> params) {
        val pages = navigators.computeIfAbsent(stackId, k -> new Stack<>());
        if (pages.isEmpty()) return;
        val oldPage = pages.peek();
        val newPage = makePage(id);
        pageId.put(newPage, id);
        assert newPage != null;
        newPage.create(container.getContext(), params);
        pages.push(newPage);
        val view = newPage.view();
        view.setBackgroundResource(R.drawable.bg);
        container.addView(view, LayoutUtil.fill());
        if (view instanceof PageLifecycle lifecycle) {
            lifecycle.onAttach();
        }
        pushPageAnimation(oldPage, newPage);
        onNavigateChange(id);
    }

    @Override
    public void pushPage(String name, Map<String, Object> params) {
        var clazz = pageMap.get(name);
        if (clazz == null) return;
        try {
            val page = clazz.newInstance();
            pushPage(page, params);
        } catch (IllegalAccessException | InstantiationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void pushPage(Page newPage, Map<String, Object> params) {
        val pages = navigators.computeIfAbsent(stackId, k -> new Stack<>());
        if (pages.isEmpty()) return;
        val oldPage = pages.peek();
        oldPage.viewWillDisappear();
        pageId.put(newPage, oldPage.id());
        newPage.create(container.getContext(), params);
        pages.push(newPage);
        val view = newPage.view();
        view.setBackgroundResource(R.drawable.bg);
        newPage.viewWillAppear();
        container.addView(view, LayoutUtil.fill());
        if (view instanceof PageLifecycle lifecycle) {
            lifecycle.onAttach();
        }
        pushPageAnimation(oldPage, newPage);
        onNavigateChange(newPage.id());
    }

    @Override
    public boolean popPage() {
        val pages = navigators.computeIfAbsent(stackId, k -> new Stack<>());
        if (pages.isEmpty()) return false;
        val oldPage = pages.pop();
        pageId.remove(oldPage);
        if (oldPage.view() instanceof PageLifecycle lifecycle) {
            lifecycle.onDetach();
        }
        val newPage = pages.peek();
        val view = newPage.view();
        container.addView(view, 0, LayoutUtil.fill());
        popPageAnimation(oldPage, newPage);
        onNavigateChange(pageId.get(newPage));
        newPage.viewDidAppear();
        return true;
    }

    void onNavigateChange(int id) {
        val actionBar = XGET(ActionBar.class);
        val toolbar = XGET(Toolbar.class);
        toolbar.invalidateMenu();
        toolbar.clear();
        val page = pageType.getOrDefault(id, PageType.NONE);
        int title = switch (page) {
            case HOME -> R.string.menu_home;
            case MESSAGE -> R.string.menu_message;
            case FILE -> R.string.menu_file;
            case STAR -> R.string.menu_star;
            case SETTING -> R.string.menu_setting;
            case SEARCH -> R.string.menu_search;
            case LOGIN -> R.string.login;
            case THEME -> R.string.setting_item_theme;
            case VIDEO_CONFIG -> R.string.setting_item_video;
            case AUDIO_CONFIG -> R.string.setting_item_audio;
            case IMAGE_CONFIG -> R.string.setting_item_picture;
            case CLOUD -> R.string.setting_item_cloud;
            case ABOUT -> R.string.setting_item_about;
            case CACHE -> R.string.setting_item_cache;
            default -> R.string.menu_unknown;
        };
        if (title != R.string.menu_unknown) {
            if (page == PageType.HOME) {
                val store = XGET(GlobalStore.class);
                XGET(NavigationTitleBar.class).setNavTitle(store.getSiteName());
            } else {
                XGET(NavigationTitleBar.class).setNavTitle(title);
            }
        }
        switch (page) {
            case HOME, SEARCH, SETTING, STAR, FILE ->  {
                actionBar.setDisplayHomeAsUpEnabled(false);
                actionBar.show();
                bottomNavigation.setVisibility(View.VISIBLE);
            }
            default -> {
                actionBar.setDisplayHomeAsUpEnabled(true);
                actionBar.show();
                bottomNavigation.setVisibility(View.GONE);
            }
        }

        if (page == PageType.PLAYER) {
            toolbar.setVisibility(View.GONE);
        } else {
            toolbar.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean canGoBack() {
        if (stackId == null) return false;
        if (!navigators.containsKey(stackId)) return false;
        return navigators.get(stackId).size() > 1;
    }

    @Override
    public Page getCurrentPage() {
        if (stackId == null) return null;
        return navigators.get(stackId).peek();
    }

    @Override
    public int getCurrentPageId() {
        if (stackId == null) return 0;
        val page = navigators.get(stackId).peek();
        return pageId.containsKey(page) ? pageId.get(page) : page.id();
    }

    void pushPageAnimation(Page oldPage, Page newPage) {
        val view = newPage.view();
        view.setVisibility(View.INVISIBLE);
        pushAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                view.setVisibility(View.VISIBLE);
                oldPage.viewWillDisappear();
                newPage.viewWillAppear();
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                container.removeView(oldPage.view());
                oldPage.viewDidDisappear();
                newPage.viewDidAppear();
            }

            @Override
            public void onAnimationRepeat(Animation animation) { }
        });
        container.post(() -> view.startAnimation(pushAnimation));
    }

    void popPageAnimation(Page oldPage, Page newPage) {
        popAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                oldPage.viewWillDisappear();
                newPage.viewWillAppear();
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                container.removeView(oldPage.view());
                oldPage.viewDidDisappear();
                newPage.viewDidAppear();
            }

            @Override
            public void onAnimationRepeat(Animation animation) { }
        });
        container.post(() -> oldPage.view().startAnimation(popAnimation));
    }
}