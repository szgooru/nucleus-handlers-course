package org.gooru.nucleus.handlers.courses.processors.repositories.activejdbc.dbhandlers;

import java.util.Map;

import org.gooru.nucleus.handlers.courses.constants.MessageConstants;
import org.gooru.nucleus.handlers.courses.processors.ProcessorContext;
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

import io.vertx.core.json.JsonObject;

public class DeleteLessonHandler implements DBHandler {

  private final ProcessorContext context;
  private static final Logger LOGGER = LoggerFactory.getLogger(DeleteLessonHandler.class);

  public DeleteLessonHandler(ProcessorContext context) {
    this.context = context;
  }

  @Override
  public ExecutionResult<MessageResponse> checkSanity() {
    if (context.courseId() == null || context.courseId().isEmpty()) {
      LOGGER.warn("invalid course id to delete lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid course id provided to delete lesson"),
              ExecutionStatus.FAILED);
    }

    if (context.unitId() == null || context.unitId().isEmpty()) {
      LOGGER.warn("invalid unit id to delete lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid unit id provided to delete lesson"),
              ExecutionStatus.FAILED);
    }

    if (context.lessonId() == null || context.lessonId().isEmpty()) {
      LOGGER.warn("invalid lesson id to delete lesson");
      return new ExecutionResult<>(MessageResponseFactory.createInvalidRequestResponse("Invalid lesson id provided to delete lesson"),
              ExecutionStatus.FAILED);
    }
    
    if (context.userId() == null || context.userId().isEmpty() || context.userId().equalsIgnoreCase(MessageConstants.MSG_USER_ANONYMOUS)) {
      LOGGER.warn("Anonymous user attempting to delete lesson");
      return new ExecutionResult<>(MessageResponseFactory.createForbiddenResponse(), ExecutionStatus.FAILED);
    }

    LOGGER.debug("checkSanity() OK");
    return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
  }

  @Override
  public ExecutionResult<MessageResponse> validateRequest() {

    LazyList<AJEntityCourse> ajEntityCourse = AJEntityCourse.findBySQL(AJEntityCourse.SELECT_COURSE_TO_VALIDATE, context.courseId());
    if (!ajEntityCourse.isEmpty()) {
      if (ajEntityCourse.get(0).getBoolean(AJEntityCourse.IS_DELETED)) {
        LOGGER.warn("course {} is deleted, hence can't delete lesson. Aborting", context.courseId());
        return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("Course is deleted for which you are trying to delete lesson"),
                ExecutionStatus.FAILED);
      }

      // TODO: Check whether user is owner or collaborator on course
    } else {
      LOGGER.warn("course {} not found to delete lesson, aborting", context.courseId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

    LazyList<AJEntityUnit> ajEntityUnit = AJEntityUnit.findBySQL(AJEntityUnit.SELECT_UNIT_TO_VALIDATE, context.unitId());
    if (!ajEntityUnit.isEmpty()) {
      if (ajEntityUnit.get(0).getBoolean(AJEntityUnit.IS_DELETED)) {
        LOGGER.warn("unit {} is deleted. Aborting", context.unitId());
        return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("Unit is deleted"), ExecutionStatus.FAILED);
      }
    } else {
      LOGGER.warn("Unit {} not found, aborting", context.unitId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

    LazyList<AJEntityLesson> ajEntityLesson = AJEntityLesson.findBySQL(AJEntityLesson.SELECT_LESSON_TO_VALIDATE, context.lessonId());
    if (!ajEntityLesson.isEmpty()) {
      if (ajEntityLesson.get(0).getBoolean(AJEntityLesson.IS_DELETED)) {
        LOGGER.warn("Lesson {} is deleted, aborting.", context.lessonId());
        return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse("Lesson is deleted"), ExecutionStatus.FAILED);
      }
    } else {
      LOGGER.warn("Lesson {} not found, aborting", context.lessonId());
      return new ExecutionResult<>(MessageResponseFactory.createNotFoundResponse(), ExecutionStatus.FAILED);
    }

    LOGGER.debug("validateRequest() OK");
    return new ExecutionResult<>(null, ExecutionStatus.CONTINUE_PROCESSING);
  }

  @Override
  public ExecutionResult<MessageResponse> executeRequest() {
    AJEntityLesson lessonToDelete = new AJEntityLesson();
    lessonToDelete.setId(context.lessonId());
    lessonToDelete.setString(AJEntityLesson.IS_DELETED, true);
    lessonToDelete.setString(AJEntityLesson.MODIFIER_ID, context.userId());

    if (lessonToDelete.save()) {
      LOGGER.info("lesson marked as deleted successfully");
      // TODO: Delete everything underneath this lesson i.e.
      // Collections/Assessments
      return new ExecutionResult<>(MessageResponseFactory.createDeleteResponse(), ExecutionStatus.SUCCESSFUL);
    } else {
      LOGGER.error("error in delete lesson");
      if (lessonToDelete.hasErrors()) {
        Map<String, String> errMap = lessonToDelete.errors();
        JsonObject errors = new JsonObject();
        errMap.forEach(errors::put);
        return new ExecutionResult<>(MessageResponseFactory.createValidationErrorResponse(errors), ExecutionStatus.FAILED);
      } else {
        return new ExecutionResult<>(MessageResponseFactory.createInternalErrorResponse("Error in deleting lesson"), ExecutionStatus.FAILED);
      }
    }
  }

  @Override
  public boolean handlerReadOnly() {
    return false;
  }
}