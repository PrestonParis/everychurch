package com.everychurch.server.faith;

import java.util.List;
import java.util.Map;

/** Wire types are also immutable snapshots. Stable dimension ids connect both questionnaires. */
public final class FaithTypes {
    private FaithTypes() {}
    public record Source(String title, String url) {}
    public record Option(String id, String label, String group) {}
    public record Dimension(String id, String title, String question, String explanation, String category,
                            List<Option> options, List<Source> sources) {}
    public record Tradition(String id, String name, String summary, Map<String,String> answers, List<Source> sources) {}
    public record Catalog(String version, String rulesVersion, String notice, List<Dimension> dimensions, List<Tradition> traditions) {}
    public record Answer(String option, int importance) {}
    public record Evaluation(String catalogVersion, Map<String,Answer> answers, String city, String state,
                             Double latitude, Double longitude, Double radiusKm) {}
    public record Statement(String option, String note, String sourceUrl) {}
    public record ProfileInput(String catalogVersion, String traditionId, Map<String,Statement> answers,
                               String participation, Long baseRevision) {}
    public record Profile(long churchId, String churchName, Long revision, String publishedAt, String confirmedBy,
                          ProfileInput content) {}
    public record Editor(Profile draft, Profile published, List<Profile> history) {}
    public record Detail(String dimensionId, String title, String userPosition, String churchPosition,
                         String outcome, boolean essential) {}
    public record Match(String id, String name, String kind, String status, Double doctrine, Double practices,
                        double coverage, double lowerBound, double upperBound, int answeredTopics,
                        int comparedTopics, Long profileRevision, String publishedAt, String participation,
                        List<Detail> details) {}
    public record Result(String catalogVersion, String rulesVersion, String evaluatedAt, String guidance,
                         List<Match> traditions, List<Match> churches, int churchesEvaluated, boolean locationProvided) {}
    public record Saved(long id, String createdAt, Evaluation request, Result result) {}
}
