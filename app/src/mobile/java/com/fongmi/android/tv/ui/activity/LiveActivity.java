package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Dimension;
import androidx.annotation.NonNull;
import androidx.core.app.ShareCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.api.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.cast.CastVideo;
import com.fongmi.android.tv.databinding.ActivityLiveBinding;
import com.fongmi.android.tv.event.ErrorEvent;
import com.fongmi.android.tv.event.PlayerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.LiveCallback;
import com.fongmi.android.tv.impl.PassCallback;
import com.fongmi.android.tv.impl.SubtitleCallback;
import com.fongmi.android.tv.model.LiveViewModel;
import com.fongmi.android.tv.player.ExoUtil;
import com.fongmi.android.tv.player.Players;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.PiPReceiver;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.ChannelAdapter;
import com.fongmi.android.tv.ui.adapter.GroupAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
import com.fongmi.android.tv.ui.custom.dialog.CastDialog;
import com.fongmi.android.tv.ui.custom.dialog.LiveDialog;
import com.fongmi.android.tv.ui.custom.dialog.PassDialog;
import com.fongmi.android.tv.ui.custom.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.custom.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Biometric;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PiP;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Utils;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Response;
import tv.danmaku.ijk.media.player.ui.IjkVideoView;

public class LiveActivity extends BaseActivity implements CustomKeyDownLive.Listener, CastDialog.Listener, PiPReceiver.Listener, TrackDialog.Listener, Biometric.Callback, PassCallback, LiveCallback, GroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, SubtitleCallback {

    private ChannelAdapter mChannelAdapter;
    private ActivityLiveBinding mBinding;
    private SimpleDateFormat mFormatDate;
    private SimpleDateFormat mFormatTime;
    private CustomKeyDownLive mKeyDown;
    private GroupAdapter mGroupAdapter;
    private LiveViewModel mViewModel;
    private PiPReceiver mReceiver;
    private List<Group> mHides;
    private Players mPlayers;
    private Channel mChannel;
    private Group mGroup;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Clock mClock;
    private boolean rotate;
    private boolean stop;
    private boolean lock;
    private int toggleCount;
    private int passCount;
    private String url;
    private PiP mPiP;

    public static void start(Activity activity) {
        if (!LiveConfig.isEmpty()) activity.startActivity(new Intent(activity, LiveActivity.class));
    }

    private PlayerView getExo() {
        return Setting.getRender() == 0 ? mBinding.surface : mBinding.texture;
    }

    private IjkVideoView getIjk() {
        return mBinding.ijk;
    }

    private Group getKeep() {
        return mGroupAdapter.get(0);
    }

    private Live getHome() {
        return LiveConfig.get().getHome();
    }

    private int getPlayerType(int playerType) {
        return playerType != -1 ? playerType : getHome().getPlayerType() != -1 ? getHome().getPlayerType() : Setting.getLivePlayer();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLiveBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.hideSystemUI(this);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mFormatTime = new SimpleDateFormat("yyyy-MM-ddHH:mm", Locale.getDefault());
        mFormatDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        mKeyDown = CustomKeyDownLive.create(this, mBinding.video);
        mClock = Clock.create(mBinding.widget.time);
        mReceiver = new PiPReceiver(this);
        mPlayers = new Players().init();
        mHides = new ArrayList<>();
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::hideInfo;
        mPiP = new PiP();
        setRecyclerView();
        setVideoView();
        setViewModel();
        getLive();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.control.seek.setListener(mPlayers);
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.lock.setOnClickListener(view -> onLock());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.share.setOnClickListener(view -> onShare());
        mBinding.control.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.home.setOnClickListener(view -> onHome());
        mBinding.control.action.line.setOnClickListener(view -> onLine());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.across.setOnClickListener(view -> onAcross());
        mBinding.control.action.change.setOnClickListener(view -> onChange());
        mBinding.control.action.player.setOnClickListener(view -> onPlayer());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.text.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new GroupAdapter(this));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
    }

    private void setPlayerView() {
        getIjk().setPlayer(mPlayers.getPlayer());
        mBinding.control.action.player.setText(mPlayers.getPlayerText());
        getExo().setVisibility(mPlayers.isExo() ? View.VISIBLE : View.GONE);
        getIjk().setVisibility(mPlayers.isIjk() ? View.VISIBLE : View.GONE);
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(getActivity(), view));
    }

    private void setDecodeView() {
        mBinding.control.action.decode.setText(mPlayers.getDecodeText());
    }

    private void setVideoView() {
        mPlayers.set(getExo(), getIjk());
        setScale(Setting.getLiveScale());
        setSubtitle(Setting.getSubtitle());
        getExo().getSubtitleView().setStyle(ExoUtil.getCaptionStyle());
        getIjk().getSubtitleView().setStyle(ExoUtil.getCaptionStyle());
        mBinding.control.action.speed.setText(mPlayers.getSpeedText());
        mBinding.control.action.across.setActivated(Setting.isAcross());
        mBinding.control.action.change.setActivated(Setting.isChange());
        mBinding.control.action.home.setVisibility(LiveConfig.isOnly() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void setSubtitle(int size) {
        getExo().getSubtitleView().setFixedTextSize(Dimension.SP, size);
        getIjk().getSubtitleView().setFixedTextSize(Dimension.SP, size);
    }

    private void setScale(int scale) {
        getExo().setResizeMode(scale);
        getIjk().setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(LiveViewModel.class);
        mViewModel.channel.observe(this, result -> mPlayers.start(result, getHome().getTimeout()));
        mViewModel.live.observe(this, live -> {
            hideProgress();
            setGroup(live);
        });
    }

    private void getLive() {
        mPlayers.setPlayer(getPlayerType(-1));
        mViewModel.getLive(getHome());
        setPlayerView();
        setDecodeView();
        showProgress();
    }

    private void setGroup(Live home) {
        List<Group> items = new ArrayList<>();
        items.add(Group.create(R.string.keep));
        for (Group group : home.getGroups()) (group.isHidden() ? mHides : items).add(group);
        mGroupAdapter.addAll(items);
        setPosition(LiveConfig.get().find(items));
        mBinding.control.action.home.setText(home.getName());
    }

    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        if (mGroupAdapter.getItemCount() == 1) return;
        if (position[0] >= mGroupAdapter.getItemCount()) return;
        mGroup = mGroupAdapter.get(position[0]);
        mGroup.setPosition(position[1]);
        onItemClick(mGroup);
        onItemClick(mGroup.current());
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        int position = mGroupAdapter.indexOf(mGroup);
        boolean change = mGroupAdapter.getPosition() != position;
        if (change) mGroupAdapter.setSelected(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        mChannelAdapter.setSelected(mGroup.getPosition());
        mBinding.channel.scrollToPosition(mGroup.getPosition());
    }

    private void onBack() {
        finish();
    }

    private void onLock() {
        setLock(!isLock());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onCast() {
        CastDialog.create().video(CastVideo.get(mBinding.control.title.getText().toString(), getUrl())).fm(false).show(this);
    }

    private void onShare() {
        ShareCompat.IntentBuilder builder = new ShareCompat.IntentBuilder(this).setType("text/plain").setText(getUrl());
        builder.getIntent().putExtra("title", mBinding.control.title.getText());
        builder.getIntent().putExtra("name", mBinding.control.title.getText());
        builder.startChooser();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(ResUtil.isLand(this) ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void onTrack(View view) {
        TrackDialog.create().player(mPlayers).type(Integer.parseInt(view.getTag().toString())).show(this);
        hideControl();
    }

    private boolean onTextLong() {
        SubtitleDialog.create(this).show();
        hideControl();
        return true;
    }

    private void onHome() {
        LiveDialog.create(this).show();
        hideControl();
    }

    private void onLine() {
        nextLine(false);
    }

    private void onScale() {
        int index = Setting.getLiveScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        Setting.putLiveScale(index = index == array.length - 1 ? 0 : ++index);
        setScale(index);
        setR1Callback();
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(mPlayers.addSpeed());
        setR1Callback();
    }

    private boolean onSpeedLong() {
        mBinding.control.action.speed.setText(mPlayers.toggleSpeed());
        setR1Callback();
        return true;
    }

    private void onAcross() {
        setR1Callback();
        Setting.putAcross(!Setting.isAcross());
        mBinding.control.action.across.setActivated(Setting.isAcross());
    }

    private void onChange() {
        setR1Callback();
        Setting.putChange(!Setting.isChange());
        mBinding.control.action.change.setActivated(Setting.isChange());
    }

    private void onPlayer() {
        mPlayers.togglePlayer();
        Setting.putLivePlayer(mPlayers.getPlayer());
        setPlayerView();
        setR1Callback();
        fetch();
    }

    private void onDecode() {
        mPlayers.toggleDecode();
        mPlayers.set(getExo(), getIjk());
        setDecodeView();
        setR1Callback();
        fetch();
    }

    private void hideUI() {
        if (isGone(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.GONE);
        setPosition();
    }

    private void showUI() {
        if (isVisible(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.VISIBLE);
        mBinding.channel.requestFocus();
        setPosition();
    }

    private void showProgress() {
        mBinding.widget.progress.setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.widget.progress.setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showControl() {
        mBinding.control.share.setVisibility(getUrl() == null ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(getUrl() == null ? View.GONE : View.VISIBLE);
        mBinding.control.rotate.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        setR1Callback();
        hideInfo();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void showInfo() {
        boolean pip = Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N && isInPictureInPictureMode();
        mBinding.widget.infoPip.setVisibility(pip ? View.VISIBLE : View.GONE);
        mBinding.widget.info.setVisibility(pip ? View.GONE : View.VISIBLE);
        setR3Callback();
        hideControl();
        setInfo();
    }

    private void hideInfo() {
        mBinding.widget.infoPip.setVisibility(View.GONE);
        mBinding.widget.info.setVisibility(View.GONE);
        App.removeCallbacks(mR3);
    }

    private void showEpg() {
        mBinding.widget.play.setText(mChannel.getData().getEpg());
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.widget.traffic);
        App.post(mR2, Constant.INTERVAL_TRAFFIC);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void setR3Callback() {
        App.post(mR3, Constant.INTERVAL_HIDE);
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else if (isVisible(mBinding.recycler)) hideUI();
        else showUI();
        hideInfo();
    }

    private void resetPass() {
        this.passCount = 0;
    }

    @Override
    public void onItemClick(Group item) {
        mGroupAdapter.setSelected(mGroup = item);
        mChannelAdapter.addAll(item.getChannel());
        mChannelAdapter.setSelected(item.getPosition());
        mBinding.channel.scrollToPosition(Math.max(item.getPosition(), 0));
        if (!item.isKeep() || ++passCount < 5 || mHides.isEmpty()) return;
        if (Biometric.enable()) Biometric.show(this);
        else PassDialog.create().show(this);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        mGroup.setPosition(mChannelAdapter.setSelected(item.group(mGroup)));
        mPlayers.setPlayer(getPlayerType(item.getPlayerType()));
        mChannel = item;
        setPlayerView();
        showInfo();
        hideUI();
        fetch();
    }

    @Override
    public boolean onLongClick(Channel item) {
        if (mGroup.isHidden()) return false;
        boolean exist = Keep.exist(item.getName());
        Notify.show(exist ? R.string.keep_del : R.string.keep_add);
        if (exist) delKeep(item);
        else addKeep(item);
        return true;
    }

    private void addKeep(Channel item) {
        getKeep().add(item);
        Keep keep = new Keep();
        keep.setKey(item.getName());
        keep.setType(1);
        keep.save();
    }

    private void delKeep(Channel item) {
        if (mGroup.isKeep()) mChannelAdapter.remove(item);
        getKeep().getChannel().remove(item);
        Keep.delete(item.getName());
    }

    private void setInfo() {
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.widget.name.setText(mChannel.getName());
        mBinding.control.title.setText(mChannel.getName());
        mBinding.widget.namePip.setText(mChannel.getName());
        mBinding.widget.line.setText(mChannel.getLineText());
        mBinding.widget.number.setText(mChannel.getNumber());
        mBinding.widget.numberPip.setText(mChannel.getNumber());
        mBinding.widget.line.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setText(mBinding.widget.line.getText());
        mBinding.control.action.line.setVisibility(mBinding.widget.line.getVisibility());
        showEpg();
        checkEpg();
    }

    private void checkEpg() {
        if (mChannel.getEpg().isEmpty()) return;
        String date = mFormatDate.format(new Date());
        String epg = mChannel.getEpg().replace("{date}", date);
        if (!mChannel.getData().equal(date)) getEpg(epg, mChannel);
    }

    private void getEpg(String epg, Channel channel) {
        OkHttp.newCall(epg).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                channel.setData(Epg.objectFrom(response.body().string(), mFormatTime));
                if (mChannel.equals(channel)) App.post(() -> showEpg());
            }
        });
    }

    private void fetch() {
        if (mChannel == null) return;
        LiveConfig.get().setKeep(mChannel);
        mViewModel.fetch(mChannel);
        showProgress();
        setUrl(null);
    }

    private void checkLockImg() {
        mBinding.control.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void release() {
        mChannelAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mChannel = null;
        mGroup = null;
    }

    @Override
    public void onTrackClick(Track item) {
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
        mPlayers.stop();
        hideControl();
        release();
        getLive();
    }

    @Override
    public void setPass(String pass) {
        unlock(pass);
    }

    @Override
    public void onBiometricSuccess() {
        unlock(null);
    }

    private void unlock(String pass) {
        boolean first = true;
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            mGroupAdapter.add(item);
            if (first) onItemClick(item);
            iterator.remove();
            first = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerEvent(PlayerEvent event) {
        switch (event.getState()) {
            case 0:
                setUrl(event.getUrl());
                setTrackVisible(false);
                break;
            case Player.STATE_IDLE:
                break;
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                checkRotate();
                resetToggle();
                hideProgress();
                mPlayers.reset();
                setSpeedVisible();
                setTrackVisible(true);
                mPiP.update(this, mPlayers.isPlaying());
                mBinding.control.size.setText(mPlayers.getSizeText());
                if (isVisible(mBinding.control.getRoot())) showControl();
                break;
            case Player.STATE_ENDED:
                nextChannel();
                break;
        }
    }

    private void checkRotate() {
        if (!isRotate() && mPlayers.isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            setRotate(true);
        }
    }

    private void setSpeedVisible() {
        mBinding.control.action.speed.setVisibility(mPlayers.isVod() ? View.VISIBLE : View.GONE);
    }

    private void setTrackVisible(boolean visible) {
        mBinding.control.action.text.setVisibility(visible && mPlayers.haveTrack(C.TRACK_TYPE_TEXT) ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(visible && mPlayers.haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.video.setVisibility(visible && mPlayers.haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onErrorEvent(ErrorEvent event) {
        if (mPlayers.addRetry() > event.getRetry()) checkError(event);
        else fetch();
    }

    private void checkError(ErrorEvent event) {
        if (getHome().getPlayerType() == -1 && event.isFormat() && getToggleCount() < 2 && mPlayers.getPlayer() != Players.SYS) {
            toggleCount++;
            nextPlayer();
        } else {
            resetToggle();
            onError(event);
        }
    }

    private void nextPlayer() {
        mPlayers.nextPlayer();
        setPlayerView();
        fetch();
    }

    private void onError(ErrorEvent event) {
        showError(event.getMsg());
        mPlayers.stop();
        startFlow();
    }

    private void startFlow() {
        if (!Setting.isChange()) return;
        if (!mChannel.isLast()) {
            nextLine(true);
        } else if (isGone(mBinding.recycler)) {
            mChannel.setLine(0);
            nextChannel();
        }
    }

    private boolean prevGroup() {
        int position = mGroupAdapter.getPosition() - 1;
        if (position < 0) position = mGroupAdapter.getItemCount() - 1;
        if (mGroup.equals(mGroupAdapter.get(position))) return false;
        mGroup = mGroupAdapter.get(position);
        mGroupAdapter.setSelected(position);
        if (mGroup.skip()) return prevGroup();
        mChannelAdapter.addAll(mGroup.getChannel());
        mGroup.setPosition(mGroup.getChannel().size() - 1);
        return true;
    }

    private boolean nextGroup() {
        int position = mGroupAdapter.getPosition() + 1;
        if (position > mGroupAdapter.getItemCount() - 1) position = 0;
        if (mGroup.equals(mGroupAdapter.get(position))) return false;
        mGroup = mGroupAdapter.get(position);
        mGroupAdapter.setSelected(position);
        if (mGroup.skip()) return nextGroup();
        mChannelAdapter.addAll(mGroup.getChannel());
        mGroup.setPosition(0);
        return true;
    }

    private void prevChannel() {
        if (mGroup == null) return;
        int position = mGroup.getPosition() - 1;
        boolean limit = position < 0;
        if (Setting.isAcross() & limit) prevGroup();
        else mGroup.setPosition(limit ? mChannelAdapter.getItemCount() - 1 : position);
        if (!mGroup.isEmpty()) onItemClick(mGroup.current());
    }

    private void nextChannel() {
        if (mGroup == null) return;
        int position = mGroup.getPosition() + 1;
        boolean limit = position > mChannelAdapter.getItemCount() - 1;
        if (Setting.isAcross() && limit) nextGroup();
        else mGroup.setPosition(limit ? 0 : position);
        if (!mGroup.isEmpty()) onItemClick(mGroup.current());
    }

    private void prevLine() {
        mChannel.prevLine();
        showInfo();
        fetch();
    }

    private void nextLine(boolean show) {
        mChannel.nextLine();
        if (show) showInfo();
        else setInfo();
        fetch();
    }

    private void seekTo() {
        mPlayers.seekTo(Constant.INTERVAL_SEEK * 3);
        showProgress();
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    public boolean isStop() {
        return stop;
    }

    public void setStop(boolean stop) {
        this.stop = stop;
    }

    public boolean isLock() {
        return lock;
    }

    public void setLock(boolean lock) {
        this.lock = lock;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getToggleCount() {
        return toggleCount;
    }

    public void resetToggle() {
        this.toggleCount = 0;
    }

    @Override
    public void onCastTo() {
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onBrightEnd() {
        mBinding.widget.bright.setVisibility(View.GONE);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onVolumeEnd() {
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onFlingUp() {
        prevChannel();
    }

    @Override
    public void onFlingDown() {
        nextChannel();
    }

    @Override
    public void onFlingLeft() {
        if (mChannel == null) return;
        if (mChannel.isOnly() && mPlayers.isVod()) App.post(this::seekTo, 250);
        else if (!mChannel.isOnly()) prevLine();
    }

    @Override
    public void onFlingRight() {
        if (mChannel == null) return;
        if (mChannel.isOnly() && mPlayers.isVod()) App.post(this::seekTo, 250);
        else if (!mChannel.isOnly()) nextLine(true);
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        else if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onControlPlay() {
        mPiP.update(this, !mPlayers.isPlaying());
        if (mPlayers.isPlaying()) mPlayers.pause();
        else mPlayers.play();
    }

    @Override
    public void onControlNext() {
        nextChannel();
    }

    @Override
    public void onControlPrev() {
        prevChannel();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        mPiP.enter(this, Setting.getLiveScale() == 2);
        if (isLock()) App.post(this::onLock, 500);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            mReceiver.register(this);
            setSubtitle(10);
            hideControl();
            hideInfo();
            hideUI();
        } else {
            hideInfo();
            mReceiver.unregister(this);
            setSubtitle(Setting.getSubtitle());
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Utils.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Utils.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setStop(false);
        if (PiP.isIn(this)) {
            PlaybackService.stop();
        } else {
            mPlayers.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        setStop(true);
        if (PiP.isIn(this)) {
            PlaybackService.start();
        } else {
            mPlayers.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
    }

    @Override
    public void onBackPressed() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (isVisible(mBinding.widget.info)) {
            hideInfo();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else if (!isLock()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPlayers.release();
        Source.get().stop();
        PlaybackService.stop();
        App.removeCallbacks(mR1, mR2, mR3);
    }
}
