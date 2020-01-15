package com.github.juggernaut.macchar;

import com.github.juggernaut.macchar.fsm.ActorStateMachine;
import com.github.juggernaut.macchar.fsm.StateMachine;
import com.github.juggernaut.macchar.fsm.events.Event;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * A bare-bones implementation of an actor system
 *
 * @author ameya
 */
public class ActorSystem {

    // Map associating an actor id to the actor object
    private final ConcurrentMap<String, Actor> actorMap = new ConcurrentHashMap<>();
    private final ExecutorService executorService;

    public ActorSystem(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * Turns a state machine into a real actor
     *
     * @param stateMachine
     * @return
     */
    public Actor createActor(final ActorStateMachine stateMachine) {
        final var actor = new DefaultActor(stateMachine);
        final var previous = actorMap.putIfAbsent(actor.getId(), actor);
        if (previous != null) {
            throw new RuntimeException("Actor with id " + actor.getId() + " already exists");
        }
        stateMachine.setActor(actor);
        stateMachine.init();
        return actor;
    }

    private class DefaultActor implements Actor {

        private final String id;
        private final StateMachine stateMachine;

        public DefaultActor(String id, StateMachine stateMachine) {
            this.id = id;
            this.stateMachine = stateMachine;
        }

        public DefaultActor(StateMachine stateMachine) {
            this(UUID.randomUUID().toString(), stateMachine);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public void sendMessage(Event event) {
            // NOTE: this assumes that the queue backing the executor service is large enough...
            executorService.submit(() -> {
                synchronized(this) {
                    try {
                        stateMachine.onEvent(event);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        @Override
        public void destroy() {
            try {
                stateMachine.shutDown();
            } finally {
                actorMap.remove(id);
            }
        }
    }

}
