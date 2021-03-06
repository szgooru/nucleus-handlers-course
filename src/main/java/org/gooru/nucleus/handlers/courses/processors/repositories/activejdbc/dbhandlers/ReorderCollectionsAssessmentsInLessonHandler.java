package org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.dbhandlers;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.gooru.nucleus.handlers.courses.constants.MessageConstants;
import org.gooru.nucleus.handlers.courses.processors.ProcessorContext;
import org.gooru.nucleus.handlers.courses.processors.events.EventBuilderFactory;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityCollection;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityCourse;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityLesson;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityUnit;
import org.gooru.nucleus.handlers.courses.processors.responses.ExecutionResult;
import org.gooru.nucleus.handlers.courses.processors.responses.ExecutionResult.ExecutionStatus;
import org.gooru.nucleus.handlers.courses.processors.responses.MessageResponse;
import org.gooru.nucleus.handlers.courses.processors.responses.MessageResponseFactory;
import org.javalite.activejdbc.Base;
import org.javalite.activejdbc.DBException;
import org.javalite.activejdbc.LazyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ReorderCollectionsAssessmentsInLessonHandler implements DBHandler {

    private final ProcessorContext context;
    private static final Logger LOGGER = LoggerFactory.getLogger(ReorderCollectionsAssessmentsInLessonHandler.class);
    private static final String REORDER_PAYLOAD_ID = "id";
    private static final String REORDER_PAYLOAD_KEY = "order";
    private static final String REORDER_PAYLOAD_SEQUENCE = "sequence_id";

    public ReorderCollectionsAssessmentsInLessonHandler(ProcessorContext context) {
        this.context = context;
    }

    @Override
    public ExecutionResult<MessageResponse> checkSanity() {
        if (context.courseId() == null || context.courseId().isEmpty()) {
            LOGGER.warn("invalid course id to reorder lesson contents");
            return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse(
                "Invalid course id provided to reorder lesson contents"), ExecutionStatus.FAILED);
        }

        if (context.unitId() == null || context.unitId().isEmpty()) {
            LOGGER.warn("invalid unit id to reorder lesson contents");
            return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse(
                "Invalid unit id provided to reorder lesson contents"), ExecutionStatus.FAILED);
        }

        if (context.lessonId() == null || context.lessonId().isEmpty()) {
            LOGGER.warn("invalid lesson id to reorder lesson contents");
            return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse(
                "Invalid unit id provided to reorder lesson contents"), ExecutionStatus.FAILED);
        }

        if (context.request() == null || context.request().isEmpty()) {
            LOGGER.warn("invalid request received to reorder lesson contents");
            return new ExecutionResult<>(
                MessageResponseFactory.createInvalidRequestResponse("Invalid data provided to reorder lesson contents"),
                ExecutionStatus.FAILED);
        }

        if (context.userId() == null || context.userId().isEmpty()
            || context.userId().equalsIgnoreCase(MessageConstants.MSG_USER_ANONYMOUS)) {
            LOGGER.warn("Anonymous user attempting to reorder lesson contents");
            return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
        }

        if (!reorderPayloadValidator(context.request().getJsonArray(REORDER_PAYLOAD_KEY))) {
            LOGGER.warn("Request data validation failed");
            return new ExecutionResult<>(
                MessageResponseFactory.createValidationErrorResponse(
                    new JsonObject().put("Reorder", "Data validation failed. Invalid data in request payload")),
                ExecutionStatus.FAILED);
        }

        LOGGER.debug("checkSanity() OK");
        return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
    }

    @Override
    public ExecutionResult<MessageResponse> validateRequest() {
        LazyList<AJEntityCourse> ajEntityCourse = AJEntityCourse.findBySQL(AJEntityCourse.SELECT_COURSE_TO_AUTHORIZE,
            context.courseId(), false, context.userId(), context.userId());
        if (ajEntityCourse.isEmpty()) {
            LOGGER.warn("user is not owner or collaborator of course to reorder lesson content. aborting");
            return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
        }

        LazyList<AJEntityUnit> ajEntityUnit =
            AJEntityUnit.findBySQL(AJEntityUnit.SELECT_UNIT_TO_VALIDATE, context.unitId(), context.courseId(), false);
        if (ajEntityUnit.isEmpty()) {
            LOGGER.warn("Unit {} not found, aborting", context.unitId());
            return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
        }

        LazyList<AJEntityLesson> ajEntityLesson = AJEntityLesson.findBySQL(AJEntityLesson.SELECT_LESSON_TO_VALIDATE,
            context.lessonId(), context.unitId(), context.courseId(), false);
        if (ajEntityLesson.isEmpty()) {
            LOGGER.warn("Lesson {} not found, aborting", context.lessonId());
            return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
        }

        LOGGER.debug("validateRequest() OK");
        return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
    }

    @Override
    public ExecutionResult<MessageResponse> executeRequest() {
        try {
            List contentOfLesson = Base.firstColumn(AJEntityCollection.SELECT_COLLECTION_OF_COURSE, context.lessonId(),
                context.unitId(), context.courseId(), false);
            JsonArray input = this.context.request().getJsonArray(REORDER_PAYLOAD_KEY);

            if (contentOfLesson.size() != input.size()) {
                return new ExecutionResult<>(
                    MessageResponseFactory.createInvalidRequestResponse("Collection/Assessment count mismatch"),
                    ExecutionResult.ExecutionStatus.FAILED);
            }

            PreparedStatement ps = Base.startBatch(AJEntityCollection.REORDER_QUERY);

            for (Object entry : input) {
                String payloadContentId = ((JsonObject) entry).getString(AJEntityCollection.ID);
                if (!contentOfLesson.contains(UUID.fromString(payloadContentId))) {
                    return new ExecutionResult<>(
                        MessageResponseFactory.createInvalidRequestResponse("Missing collection(s)/assessment(s)"),
                        ExecutionResult.ExecutionStatus.FAILED);
                }

                int sequenceId = ((JsonObject) entry).getInteger(AJEntityCollection.SEQUENCE_ID);
                Base.addBatch(ps, sequenceId, this.context.userId(), payloadContentId, context.lessonId(),
                    context.unitId(), context.courseId(), false);
            }

            Base.executeBatch(ps);

            AJEntityCourse courseToUpdate = new AJEntityCourse();
            courseToUpdate.setCourseId(context.courseId());
            courseToUpdate.setTimestamp(AJEntityCourse.UPDATED_AT, new Timestamp(System.currentTimeMillis()));
            boolean result = courseToUpdate.save();
            if (!result) {
                LOGGER.error("Course with id '{}' failed to save modified time stamp", context.courseId());
                if (courseToUpdate.hasErrors()) {
                    Map<String, String> map = courseToUpdate.errors();
                    JsonObject errors = new JsonObject();
                    map.forEach(errors::put);
                    return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(errors),
                        ExecutionStatus.FAILED);
                }
            }

        } catch (DBException | ClassCastException e) {
            LOGGER.error("incorrect payload data type", e);
            return new ExecutionResult<>(
                MessageResponseFactory.createInvalidRequestResponse("Incorrect payload data types"),
                ExecutionResult.ExecutionStatus.FAILED);
        }
        LOGGER.info("reordered contents of in lesson {}", context.lessonId());
        return new ExecutionResult<>(MessageResponseFactory.createNoContentResponse(
            EventBuilderFactory.getReorderCollectionEventBuilder(context.lessonId())), ExecutionStatus.SUCCESSFUL);
    }

    @Override
    public boolean handlerReadOnly() {
        return false;
    }

    private boolean reorderPayloadValidator(Object value) {
        if (!(value instanceof JsonArray) || value == null || ((JsonArray) value).isEmpty()) {
            return false;
        }
        JsonArray input = (JsonArray) value;
        List<Integer> sequences = new ArrayList<>(input.size());
        for (Object o : input) {
            if (!(o instanceof JsonObject)) {
                return false;
            }
            JsonObject entry = (JsonObject) o;
            if ((entry.getMap().keySet().isEmpty() || entry.getMap().keySet().size() != 2)) {
                return false;
            }
            try {
                Integer sequence = entry.getInteger(REORDER_PAYLOAD_SEQUENCE);
                if (sequence == null) {
                    return false;
                }
                String idString = entry.getString(REORDER_PAYLOAD_ID);
                UUID id = UUID.fromString(idString);
                sequences.add(sequence);
            } catch (ClassCastException | IllegalArgumentException e) {
                return false;
            }
        }
        if (sequences.size() != input.size()) {
            return false;
        }
        for (int i = 1; i <= input.size(); i++) {
            if (!sequences.contains(i)) {
                return false;
            }
        }
        return true;
    }

}
