package __PACKAGE__;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowService;

import java.time.Duration;

import android.media.AudioManager;
import android.os.Looper;

import java.util.Collections;
import android.content.pm.ServiceInfo;

// The service on its own: the foreground notification, the wake lock held for as long as it runs
// and never longer, and a start that is not sticky.
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShellVoiceServiceTest {

    @Before
    public void setUp() {
        ShellVoiceService.wanted = true;
        ShellVoiceService.foreground = false;
    }

    private static Intent intent() {
        return new Intent(ApplicationProvider.getApplicationContext(), ShellVoiceService.class);
    }

    private static boolean held() {
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        return lock != null && lock.isHeld();
    }

    @Test
    public void runsInTheForegroundWithAnOngoingNotificationThatReopensTheApp() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        int result = controller.get().onStartCommand(intent(), 0, 1);
        assertEquals(Service.START_NOT_STICKY, result);
        ShadowService shadow = shadowOf(controller.get());
        Notification notification = shadow.getLastForegroundNotification();
        assertNotNull(notification);
        assertTrue((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals("Conversa por voz em andamento", notification.extras.getString(Notification.EXTRA_TEXT));
        assertNotNull("a tap brings the app back", notification.contentIntent);
        assertEquals(ShellVoiceService.CHANNEL_ID, notification.getChannelId());
    }

    @Test
    public void holdsAPartialWakeLockWhileItRunsAndReleasesItOnDestroy() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        assertTrue(held());
        controller.destroy();
        assertFalse(held());
    }

    @Test
    public void startingTwiceHoldsOneLockAndOneDestroyReleasesIt() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        controller.get().onStartCommand(intent(), 0, 2);
        controller.destroy();
        assertFalse(held());
    }

    @Test
    public void removingTheTaskStopsIt() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        controller.get().onTaskRemoved(intent());
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void goesForegroundWithTheMicrophoneType() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, controller.get().getForegroundServiceType());
        assertTrue(ShellVoiceService.foreground);
    }

    @Test
    public void aWithdrawnRequestStillGoesForegroundFirstAndThenStops() {
        ShellVoiceService.wanted = false;
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        int result = controller.get().onStartCommand(intent(), 0, 1);
        assertEquals(Service.START_NOT_STICKY, result);
        ShadowService shadow = shadowOf(controller.get());
        assertNotNull("startForeground is owed to the system before stopping", shadow.getLastForegroundNotification());
        assertTrue(shadow.isStoppedBySelf());
        assertFalse("no lock for a request that was withdrawn", held());
    }

    @Test
    public void stopWithoutForegroundOnlyWithdrawsTheRequest() {
        ShellVoiceService.stop(ApplicationProvider.getApplicationContext());
        assertFalse(ShellVoiceService.wanted);
        assertNull(shadowOf((android.app.Application) ApplicationProvider.getApplicationContext()).getNextStoppedService());
    }

    // startForeground refused (permission revoked, microphone start no longer allowed).
    public static class RefusingService extends ShellVoiceService {
        @Override
        Notification notification() {
            throw new SecurityException("refused");
        }
    }

    @Test
    public void aRefusedStartForegroundClearsTheRequestAndStopsTheService() {
        ServiceController<RefusingService> controller = Robolectric.buildService(RefusingService.class).create();
        int result = controller.get().onStartCommand(intent(), 0, 1);
        assertEquals(Service.START_NOT_STICKY, result);
        assertFalse(ShellVoiceService.wanted);
        assertFalse(ShellVoiceService.foreground);
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void stopClearsForegroundBeforeStoppingSoAnImmediateRetryDoesNotSeeAStaleTrue() {
        ShellVoiceService.foreground = true;
        ShellVoiceService.wanted = true;
        ShellVoiceService.stop(ApplicationProvider.getApplicationContext());
        assertFalse(ShellVoiceService.foreground);
    }

    @Test
    public void anActiveRecordingKeepsTheServiceAliveAcrossChecks() {
        AudioManager audio = ApplicationProvider.getApplicationContext().getSystemService(AudioManager.class);
        shadowOf(audio).setActiveRecordingConfigurations(
            Collections.singletonList(shadowOf(audio).createActiveRecordingConfiguration(0, 0, "")), false);
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        for (int i = 0; i < 4; i++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShellVoiceService.CHECK_MS));
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());
        assertTrue(ShellVoiceService.wanted);
        assertTrue(held());
    }

    @Test
    public void stopStopsTheServiceOnceItIsForeground() {
        ShellVoiceService.foreground = true;
        ShellVoiceService.wanted = true;
        ShellVoiceService.stop(ApplicationProvider.getApplicationContext());
        assertFalse(ShellVoiceService.wanted);
        assertNotNull(shadowOf((android.app.Application) ApplicationProvider.getApplicationContext()).getNextStoppedService());
    }

    @Test
    public void stopsAfterTwoChecksWithoutAnActiveRecording() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        ShadowService shadow = shadowOf(controller.get());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShellVoiceService.CHECK_MS + 100));
        assertFalse("one check without a recording is not enough", shadow.isStoppedBySelf());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShellVoiceService.CHECK_MS));
        assertTrue(shadow.isStoppedBySelf());
        assertFalse(ShellVoiceService.wanted);
    }

    @Test
    public void anActiveRecordingResetsTheIdleCount() {
        assertEquals(0, ShellVoiceService.nextIdleChecks(true, 1));
        assertEquals(1, ShellVoiceService.nextIdleChecks(false, 0));
        assertEquals(ShellVoiceService.IDLE_CHECKS_BEFORE_STOP, ShellVoiceService.nextIdleChecks(false, ShellVoiceService.IDLE_CHECKS_BEFORE_STOP - 1));
    }

    @Test
    public void theWakeLockIsBoundedAndTheCheckRenewsIt() {
        ServiceController<ShellVoiceService> controller = Robolectric.buildService(ShellVoiceService.class).create();
        controller.get().onStartCommand(intent(), 0, 1);
        assertTrue(held());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ShellVoiceService.CHECK_MS + 100));
        assertTrue("renewed by the check, not lapsed", held());
    }
}
