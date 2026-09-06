package worldsim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;
import worldsim.dto.AgentPosDto;
import worldsim.dto.LiveFrameDto;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint("/world/live")
@ApplicationScoped
public class WorldWebSocket {

    private static final Logger LOG = Logger.getLogger(WorldWebSocket.class);
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @Inject
    ObjectMapper objectMapper;

    @Inject
    PhysicsService physicsService;

    @Inject
    TickMetrics tickMetrics;

    @OnOpen
    public void onOpen(Session session) {
        SESSIONS.add(session);
        try {
            session.getBasicRemote().sendText(toJson(snapshot()));
        } catch (Exception e) {
            LOG.error("Failed to send live snapshot", e);
        }
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        SESSIONS.remove(session);
        LOG.debug("Live socket error", error);
    }

    public int clientCount() {
        return SESSIONS.size();
    }

    public void broadcastPositions(List<AgentPosDto> agents) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        String json = toJson(new LiveFrameDto(agents, metrics().toDto()));
        if (json == null) {
            return;
        }
        for (Session session : SESSIONS) {
            sendText(session, json);
        }
    }

    private LiveFrameDto snapshot() {
        return new LiveFrameDto(physics().agentPositions(), metrics().toDto());
    }

    private void sendText(Session session, String json) {
        if (!session.isOpen()) {
            SESSIONS.remove(session);
            return;
        }
        try {
            session.getAsyncRemote().sendText(json, result -> {
                if (result == null || !result.isOK()) {
                    SESSIONS.remove(session);
                }
            });
        } catch (Exception e) {
            SESSIONS.remove(session);
        }
    }

    private String toJson(LiveFrameDto frame) {
        try {
            return mapper().writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize live frame", e);
            return null;
        }
    }

    private ObjectMapper mapper() {
        return objectMapper != null ? objectMapper : CDI.current().select(ObjectMapper.class).get();
    }

    private PhysicsService physics() {
        return physicsService != null ? physicsService : CDI.current().select(PhysicsService.class).get();
    }

    private TickMetrics metrics() {
        return tickMetrics != null ? tickMetrics : CDI.current().select(TickMetrics.class).get();
    }
}
