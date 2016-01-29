package org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.dbhandlers;

import org.gooru.nucleus.handlers.courses.constants.MessageConstants;
import org.gooru.nucleus.handlers.courses.processors.ProcessorContext;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityCollection;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityContent;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityCourse;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityLesson;
import org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.entities.AJEntityUnit;
import org.gooru.nucleus.handlers.courses.processors.responses.ExecutionResult;
import org.gooru.nucleus.handlers.courses.processors.responses.MessageResponse;
import org.gooru.nucleus.handlers.courses.processors.responses.MessageResponseFactory;
import org.gooru.nucleus.handlers.courses.processors.responses.ExecutionResult.ExecutionStatus;
import org.javalite.activejdbc.LazyList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class MoveLessonToUnitHandler implements DBHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(MoveLessonToUnitHandler.class);
  private final ProcessorContext context;
  private AJEntityLesson lessonToUpdate;
  private String targetCourseOwner;

  public MoveLessonToUnitHandler(ProcessorContext context) {
    this.context = context;
  }

  @Override
  public ExecutionResult<MessageResponse> checkSanity() {
    if (context.courseId() == null || context.courseId().isEmpty()) {
      LOGGER.warn("invalid course id to move lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid course id to move lesson"), ExecutionStatus.FAILED);
    }

    if (context.unitId() == null || context.unitId().isEmpty()) {
      LOGGER.warn("invalid unit id to move lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid unit id to move lesson"), ExecutionStatus.FAILED);
    }

    if (context.userId() == null || context.userId().isEmpty() || context.userId().equalsIgnoreCase(MessageConstants.MSG_USER_ANONYMOUS)) {
      LOGGER.warn("Anonymous user attempting to move lesson");
      return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
    }

    if (context.request() == null || context.request().isEmpty()) {
      LOGGER.warn("invalid data provided to move lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid data provided to move lesson"),
              ExecutionStatus.FAILED);
    }

    JsonObject validateErrors = validateFields();
    if (validateErrors != null && !validateErrors.isEmpty()) {
      return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(validateErrors), ExecutionResult.ExecutionStatus.FAILED);
    }

    JsonObject notNullErrors = validateNullFields();
    if (notNullErrors != null && !notNullErrors.isEmpty()) {
      return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(notNullErrors), ExecutionResult.ExecutionStatus.FAILED);
    }

    // TODO: check all required fields exists in request payload

    LOGGER.debug("checkSanity() OK");
    return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
  }

  @Override
  public ExecutionResult<MessageResponse> validateRequest() {
    String targetCourseId = context.courseId();
    String targetUnitId = context.unitId();
    String sourceCourseId = context.request().getString("course_id");
    String sourceUnitId = context.request().getString("unit_id");
    String lessonToMove = context.request().getString("lesson_id");

    LazyList<AJEntityCourse> targetCourses = AJEntityCourse.findBySQL(AJEntityCourse.SELECT_COURSE_TO_VALIDATE, targetCourseId);
    LazyList<AJEntityCourse> sourceCourses = AJEntityCourse.findBySQL(AJEntityCourse.SELECT_COURSE_TO_VALIDATE, sourceCourseId);

    if (targetCourses.isEmpty() || sourceCourses.isEmpty()) {
      LOGGER.debug("source or target course is not found in database");
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("source or target course is deleted"), ExecutionStatus.FAILED);
    }

    AJEntityCourse targetCourse = targetCourses.get(0);
    AJEntityCourse sourceCourse = sourceCourses.get(0);

    if (targetCourse.getBoolean(AJEntityCourse.IS_DELETED) || sourceCourse.getBoolean(AJEntityCourse.IS_DELETED)) {
      LOGGER.info("source or target course is deleted, hence can't move lesson. Aborting", context.courseId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("source or target course is deleted"), ExecutionStatus.FAILED);
    }

    targetCourseOwner = targetCourse.getString(AJEntityCourse.OWNER_ID);
    if (!targetCourseOwner.equalsIgnoreCase(context.userId())) {
      if (!new JsonArray(targetCourse.getString(AJEntityCourse.COLLABORATOR)).contains(context.userId())) {
        LOGGER.warn("user is not owner or collaborator of target course to move lesson. aborting");
        return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
      }
    }

    if (!sourceCourse.getString(AJEntityCourse.OWNER_ID).equalsIgnoreCase(context.userId())) {
      if (!new JsonArray(sourceCourse.getString(AJEntityCourse.COLLABORATOR)).contains(context.userId())) {
        LOGGER.warn("user is not owner or collaborator of source course to move lesson. aborting");
        return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
      }
    }

    // Check whether the source unit is exists, not deleted and associated with
    // source course
    LazyList<AJEntityUnit> sourceUnits = AJEntityUnit.findBySQL(AJEntityUnit.SELECT_UNIT_TO_VALIDATE, sourceUnitId);
    if (!sourceUnits.isEmpty()) {
      AJEntityUnit unit = sourceUnits.get(0);
      if (unit.getBoolean(AJEntityUnit.IS_DELETED)) {
        LOGGER.warn("source unit {} is deleted. Aborting", context.unitId());
        return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("source unit is deleted"), ExecutionStatus.FAILED);
      }

      if (!unit.getString(AJEntityUnit.COURSE_ID).equalsIgnoreCase(sourceCourseId)) {
        LOGGER.debug("source unit is not associated with source course");
        return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("source unit is not associated with source course"),
                ExecutionStatus.FAILED);
      }
    } else {
      LOGGER.warn("Unit {} not found to move, aborting", context.unitId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

    // Check whether the target unit is exists, not deleted and associated with
    // target course
    LazyList<AJEntityUnit> targetUnits = AJEntityUnit.findBySQL(AJEntityUnit.SELECT_UNIT_TO_VALIDATE, targetUnitId);
    if (!targetUnits.isEmpty()) {
      AJEntityUnit unit = targetUnits.get(0);
      if (unit.getBoolean(AJEntityUnit.IS_DELETED)) {
        LOGGER.warn("target unit {} is deleted. Aborting", context.unitId());
        return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("target unit is deleted"), ExecutionStatus.FAILED);
      }

      if (!unit.getString(AJEntityUnit.COURSE_ID).equalsIgnoreCase(targetCourseId)) {
        LOGGER.debug("target unit is not associated with target course");
        return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("target unit is not associated with target course"),
                ExecutionStatus.FAILED);
      }
    } else {
      LOGGER.warn("Unit {} not found to move, aborting", context.unitId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

    // Check whether the lesson is exists, not deleted and associated with
    // source unit and course
    LazyList<AJEntityLesson> lessons = AJEntityLesson.findBySQL(AJEntityLesson.SELECT_LESSON_TO_VALIDATE, lessonToMove);
    if (lessons.isEmpty()) {
      LOGGER.warn("Lesson {} not found to move, aborting", context.unitId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

   lessonToUpdate = lessons.get(0);
    if (lessonToUpdate.getBoolean(AJEntityLesson.IS_DELETED)) {
      LOGGER.warn("lesson {} is deleted. Aborting", context.unitId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("Lesson is deleted"), ExecutionStatus.FAILED);
    }

    if (!lessonToUpdate.getString(AJEntityLesson.UNIT_ID).equalsIgnoreCase(sourceUnitId)) {
      LOGGER.debug("Lesson is not associated with source unit");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Lesson is not associated with source unit"),
              ExecutionStatus.FAILED);
    }

    if (!lessonToUpdate.getString(AJEntityLesson.COURSE_ID).equalsIgnoreCase(sourceCourseId)) {
      LOGGER.debug("lesson is not associated with source course");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Lesson is not associated with source course"),
              ExecutionStatus.FAILED);
    }

    LOGGER.debug("validateRequest() OK");
    return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
  }

  @Override
  public ExecutionResult<MessageResponse> executeRequest() {
    lessonToUpdate.setCourseId(context.courseId());
    lessonToUpdate.setUnitId(context.unitId());
    lessonToUpdate.setModifierId(context.userId());
    lessonToUpdate.setOwnerId(targetCourseOwner);

    if (lessonToUpdate.hasErrors()) {
      LOGGER.debug("moving lesson has errors");
      return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(getModelErrors()), ExecutionStatus.FAILED);
    }

    if (lessonToUpdate.save()) {
      LOGGER.info("lesson is moved to unit and course");
      AJEntityCollection.update("course_id = ?::uuid, unit_id = ?::uuid, owner_id = ?::uuid, modifier_id = ?::uuid, collaborator = ?",
              "lesson_id = ?::uuid", context.courseId(), context.unitId(), targetCourseOwner, context.userId(), null, lessonToUpdate.getId());
      AJEntityContent.update("course_id = ?::uuid, unit_id = ?::uuid, modifier_id = ?::uuid", "lesson_id = ?::uuid", context.courseId(),
              context.unitId(), context.userId(), lessonToUpdate.getId());
      return new ExecutionResult<>(MessageResponseFactory.createPutResponse(context.courseId()), ExecutionStatus.SUCCESSFUL);
    } else {
      LOGGER.debug("error while moving lesson to course and unit");
      return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(getModelErrors()), ExecutionStatus.FAILED);
    }
  }

  @Override
  public boolean handlerReadOnly() {
    return false;
  }

  private JsonObject validateFields() {
    JsonObject input = context.request();
    JsonObject output = new JsonObject();
    input.fieldNames().stream().filter(key -> !AJEntityUnit.LESSON_MOVE_NOTNULL_FIELDS.contains(key))
            .forEach(key -> output.put(key, "Field not allowed"));
    return output.isEmpty() ? null : output;
  }

  private JsonObject validateNullFields() {
    JsonObject input = context.request();
    JsonObject output = new JsonObject();
    input.fieldNames().stream()
            .filter(key -> AJEntityUnit.LESSON_MOVE_NOTNULL_FIELDS.contains(key)
                    && (input.getValue(key) == null || input.getValue(key).toString().isEmpty()))
            .forEach(key -> output.put(key, "Field should not be empty or null"));
    return output.isEmpty() ? null : output;
  }

  private JsonObject getModelErrors() {
    JsonObject errors = new JsonObject();
    this.lessonToUpdate.errors().entrySet().forEach(entry -> errors.put(entry.getKey(), entry.getValue()));
    return errors;
  }

}
