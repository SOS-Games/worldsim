package worldsim.dto;

public record StuckAgentDto(
        long id,
        String name,
        String job,
        int x,
        int y,
        String tile,
        String resource,
        int carried,
        Integer targetX,
        Integer targetY,
        int pathLen) {}
