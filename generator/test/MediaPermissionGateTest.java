package __PACKAGE__;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Behavioral test of MediaPermissionGate on a plain JVM, no framework: the template is compiled
// with its placeholder package and this file in the same one. Exit code 1 on the first failure.
//
// javac -d build generator/templates/android/MediaPermissionGate.java generator/test/MediaPermissionGateTest.java
// java -cp build __PACKAGE__.MediaPermissionGateTest
//
public final class MediaPermissionGateTest {

    // A request is a name; the fake host records what the gate asks of it.
    static final class FakeHost implements MediaPermissionGate.Host<String> {
        final Set<String> held = new HashSet<>();
        boolean declared = true;
        boolean appHost = true;
        boolean resumed = true;
        final List<String> granted = new ArrayList<>();
        final List<String> denied = new ArrayList<>();
        final List<String> launches = new ArrayList<>();
        final Set<String> alreadyResolved = new HashSet<>();

        @Override public List<String> needs(String request) {
            return request.startsWith("other") ? new ArrayList<String>() : Arrays.asList("RECORD_AUDIO");
        }
        @Override public boolean allDeclared(List<String> permissions) { return declared; }
        @Override public boolean allHeld(List<String> permissions) { return held.containsAll(permissions); }
        @Override public boolean fromAppHost(String request) { return appHost; }
        @Override public void grant(String request) {
            if (alreadyResolved.contains(request)) throw new IllegalStateException("Either grant() or deny() has been already called.");
            granted.add(request);
        }
        @Override public void deny(String request) {
            if (alreadyResolved.contains(request)) throw new IllegalStateException("Either grant() or deny() has been already called.");
            denied.add(request);
        }
        @Override public void launchDialog(String request) { launches.add(request); }
        @Override public boolean isResumed() { return resumed; }
    }

    static int checks = 0;

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
    }

    static void sameList(List<String> actual, String message, String... expected) {
        check(actual.equals(Arrays.asList(expected)), message + ": expected " + Arrays.asList(expected) + " got " + actual);
    }

    public static void main(String[] args) {
        // Held permissions are granted at once, with no dialog.
        FakeHost h = new FakeHost();
        h.held.add("RECORD_AUDIO");
        MediaPermissionGate<String> g = new MediaPermissionGate<>(h);
        check(g.handle("a"), "a capture request is handled");
        sameList(h.granted, "held: granted", "a");
        sameList(h.launches, "held: no dialog");

        // A request that is not for capture is left to the bridge.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        check(!g.handle("other"), "a non-capture request is not handled");
        sameList(h.granted, "non-capture: untouched");
        sameList(h.denied, "non-capture: untouched");

        // Foreign origin and undeclared permission are denied without a dialog.
        h = new FakeHost();
        h.appHost = false;
        g = new MediaPermissionGate<>(h);
        g.handle("f");
        sameList(h.denied, "foreign origin: denied", "f");
        sameList(h.launches, "foreign origin: no dialog");
        h = new FakeHost();
        h.declared = false;
        g = new MediaPermissionGate<>(h);
        g.handle("u");
        sameList(h.denied, "undeclared: denied", "u");
        sameList(h.launches, "undeclared: no dialog");

        // First request launches once, the second queues, the answer with the permission held grants both once.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onActivityPaused();
        h.resumed = false;
        g.handle("b");
        sameList(h.launches, "one dialog for two requests", "a");
        sameList(h.granted, "nothing answered while the dialog is up");
        h.held.add("RECORD_AUDIO");
        g.onDialogResult();
        sameList(h.granted, "answer with the permission held grants both once", "a", "b");
        sameList(h.denied, "none denied");
        g.onDialogResult();
        sameList(h.granted, "a second answer does nothing", "a", "b");

        // Answer without the permission denies both, whatever the dialog reported.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onActivityPaused();
        h.resumed = false;
        g.handle("b");
        g.onDialogResult();
        sameList(h.denied, "answer without the permission denies both", "a", "b");
        sameList(h.granted, "none granted");
        g.onDialogResult();
        sameList(h.denied, "a second answer does nothing (denied)", "a", "b");

        // Cancel removes a queued request: it is never answered.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.handle("b");
        g.cancel("b");
        h.held.add("RECORD_AUDIO");
        g.onDialogResult();
        sameList(h.granted, "a cancelled request is not answered", "a");

        // Lost answer: a dialog in progress, the activity paused for it and resumed again, a new request:
        // the old queue is settled by the permissions held and a fresh dialog starts.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onActivityPaused();
        h.resumed = true;
        g.handle("b");
        sameList(h.denied, "lost answer: the old queue is settled by the permissions held", "a");
        sameList(h.launches, "lost answer: a fresh dialog starts", "a", "b");
        h.held.add("RECORD_AUDIO");
        g.onDialogResult();
        sameList(h.granted, "lost answer: the new request gets the fresh answer", "b");

        // Lost answer, permission granted meanwhile: the old queue is granted.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onActivityPaused();
        h.held.add("RECORD_AUDIO");
        g.handle("b");
        sameList(h.granted, "lost answer with the permission now held: both granted", "a", "b");
        sameList(h.launches, "no second dialog once the permission is held", "a");

        // Dialog in progress and the activity not resumed: queued, no second dialog.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onActivityPaused();
        h.resumed = false;
        g.handle("b");
        sameList(h.launches, "not resumed: queued without a second dialog", "a");
        sameList(h.denied, "not resumed: nothing settled");

        // A request that arrives right after the launch, before the activity paused, is queued too.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        h.resumed = true;
        g.handle("b");
        sameList(h.launches, "same tick: the dialog is not shown yet, queued", "a");
        sameList(h.denied, "same tick: nothing settled");

        // A request the page side already resolved (grant throws) does not break the others.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.handle("b");
        g.handle("c");
        h.alreadyResolved.add("b");
        h.held.add("RECORD_AUDIO");
        g.onDialogResult();
        sameList(h.granted, "a resolved request does not stop the others", "a", "c");

        // After a settle the gate takes new requests normally.
        h = new FakeHost();
        g = new MediaPermissionGate<>(h);
        g.handle("a");
        g.onDialogResult();
        g.handle("b");
        sameList(h.launches, "a new dialog after the last one closed", "a", "b");

        System.out.println("MediaPermissionGateTest ok (" + checks + " checks)");
    }
}
