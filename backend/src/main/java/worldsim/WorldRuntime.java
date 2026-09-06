package worldsim;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class WorldRuntime {
    private volatile boolean ready;
    private volatile boolean shuttingDown;

    void onStop(@Observes ShutdownEvent event) {
        shuttingDown = true;
        ready = false;
    }

    public void markReady() {
        if (!shuttingDown) {
            ready = true;
        }
    }

    public boolean isActive() {
        return ready && !shuttingDown;
    }
}
