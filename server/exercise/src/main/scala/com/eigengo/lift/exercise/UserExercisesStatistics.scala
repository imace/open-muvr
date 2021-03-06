package com.eigengo.lift.exercise

import akka.actor.{ActorLogging, Props}
import akka.contrib.pattern.ShardRegion
import akka.persistence.PersistentView
import com.eigengo.lift.Exercise._
import com.eigengo.lift.common.UserId
import com.eigengo.lift.exercise.UserExercises.{ExerciseSuggestionsSetEvt, SessionEndedEvt, SessionStartedEvt, ExerciseEvt}
import com.eigengo.lift.exercise.UserExercisesStatistics.ExerciseStatistics.Entry

import scala.language.postfixOps
import scalaz.\/

object UserExercisesStatistics {
  /** The shard name */
  val shardName = "user-exercises-statistics"
  /** The props to create the actor on a node */
  def props() = Props(classOf[UserExercisesStatistics])

  val supportedMuscleGroups = List(
    MuscleGroup(key = "legs",  title = "Legs",  exercises = List("squat", "leg press", "leg extension", "leg curl", "lunge")),
    MuscleGroup(key = "core",  title = "Core",  exercises = List("crunch", "side bend", "cable crunch", "sit up", "leg raises")),
    MuscleGroup(key = "back",  title = "Back",  exercises = List("pull up", "row", "deadlift", "hyper-extension")),
    MuscleGroup(key = "arms",  title = "Arms",  exercises = List("bicep curl", "hammer curl", "pronated curl", "tricep push down", "tricep overhead extension", "tricep dip", "close-grip bench press")),
    MuscleGroup(key = "chest", title = "Chest", exercises = List("chest press", "butterfly", "cable cross-over", "incline chest press", "push up")),
    MuscleGroup(key = "shoulders", title = "Shoulders", exercises = List("shoulder press", "lateral raise", "front raise", "rear raise", "upright row", "shrug")),
    MuscleGroup(key = "cardiovascular", title = "Cardiovascular", exercises = List("running", "cycling", "swimming", "elliptical", "rowing"))
  )
  // all exercises
  private val allExerciseEntries: List[Entry] =
    supportedMuscleGroups.flatMap { mg ⇒
      mg.exercises.map { e ⇒
        Entry(mg.key, 0, 0, Exercise(e, None, None))
      }
    }

  /**
   * Companion object defining the table "entry" and other convenience entities
   */
  object ExerciseStatistics {

    /**
     * The "row" in a "table" of exercises
     * @param key the muscle group key
     * @param intendedIntensity the session's intended intensity
     * @param count the count of exercises
     * @param exercise the exercise
     */
    case class Entry(key: MuscleGroupKey, intendedIntensity: ExerciseIntensity, count: Int, exercise: Exercise) {
      /**
       * Return an Entry with incremented count
       * @return new entry
       */
      def inc(): Entry = copy(count = count + 1)

      /**
       * Computes whether the given ``sessionProperties`` and ``exercise`` match the values in the row
       *
       * @param sessionProperties the session props
       * @param exercise the exercise
       * @return true if this row represents the given ``exercise`` in the ``sessionProperties``
       */
      def matches(sessionProperties: SessionProperties, exercise: Exercise): Boolean = {

        def matches(e1: Exercise, e2: Exercise): Boolean = {
          e1.name == e2.name && ((e1.intensity, e2.intensity) match {
            case (Some(i1), Some(i2)) ⇒ i1 ~~ i2
            case _ ⇒ false
          })
        }

        sessionProperties.muscleGroupKeys.contains(key) &&
        intendedIntensity ~~ sessionProperties.intendedIntensity &&
        matches(this.exercise, exercise)
      }
    }

    /** Empty ExerciseStatistics */
    val empty: ExerciseStatistics = ExerciseStatistics(List.empty)
  }

  /**
   * Maintains exercise statistics
   * @param statistics the statistics
   */
  case class ExerciseStatistics(statistics: List[ExerciseStatistics.Entry]) {
    import ExerciseStatistics._

    /**
     * Compute the exerises ordered by their counts, with intensity set to average intensity in the set
     * identified by the filter
     * @param muscleGroups filter on muscle groups
     * @param intendedIntensity filter on intended intensity
     * @return the exercises, ordered by count
     */
    private def examples(muscleGroups: Option[Seq[MuscleGroupKey]], intendedIntensity: Option[Double]): List[Exercise] = {
      def exerciseByName(l: Exercise, r: Exercise): Boolean = {
        l.name < r.name
      }

      def mgkFilter(entry: Entry): Boolean = {
        muscleGroups.map(_.contains(entry.key)).getOrElse(true)
      }

      def intensityFilter(entry: Entry): Boolean = {
        intendedIntensity.map(entry.intendedIntensity ~~).getOrElse(true)
      }

      val userExercises = statistics.filter(e ⇒ mgkFilter(e) && intensityFilter(e)).sortBy(_.count).groupBy(_.exercise.name).map {
        case (name, entries) ⇒ Exercise(name, Some(entries.map(_.exercise.intensity.getOrElse(0.5)).sum / entries.size), None)
      }.toList

      val rest = allExerciseEntries.filter(mgkFilter).map(_.exercise).filterNot(e ⇒ userExercises.exists(ue ⇒ ue.name == e.name))

      userExercises ++ rest.sortWith(exerciseByName)
    }

    /**
     * Examples filtered by the given muscle groups and intended intensity
     * @param muscleGroups the muscle groups
     * @param intendedIntensity the intended intensity
     * @return
     */
    def examples(muscleGroups: Seq[MuscleGroupKey], intendedIntensity: Double): List[Exercise] = {
      examples(Some(muscleGroups), Some(intendedIntensity))
    }

    /**
     * Examples filtered by the given muscle groups
     * @param muscleGroups the muscle groups 
     * @return the examples
     */
    def examples(muscleGroups: Seq[MuscleGroupKey]): List[Exercise] = {
      examples(Some(muscleGroups), None)
    }

    /**
     * Exercise examples across all exercises
     * @return the examples
     */
    def examples(): List[Exercise] = {
      examples(None, None)
    }

    /**
     * Returns a new ExerciseStatistics with the new exercises added or its matching record incremented
     * @param sessionProperties the session properties
     * @param exercise the exercise to be added
     * @return the updated this
     */
    def withNewExercise(sessionProperties: SessionProperties, exercise: Exercise): ExerciseStatistics = {
      val x = statistics.indexWhere(_.matches(sessionProperties, exercise)) match {
        case -1 ⇒ statistics ++ sessionProperties.muscleGroupKeys.map(k ⇒ Entry(k, sessionProperties.intendedIntensity, 1, exercise))
        case i ⇒ statistics.updated(i, statistics(i).inc())
      }
      ExerciseStatistics(x)
    }

  }

  /**
   * Get the classification examples for the given user and session. The user identity must be set, in which case, the response
   * will contain the exercises for that user across all the user's sessions. If the ``sessionId`` is set, then the response
   * will contian the exercises matching the sessions properties. Somewhat redundantly, it is possible to specify ``None`` for
   * ``sessionId`` and ``Some(Seq(x: String))`` for ``muscleGroupKeys``, and the response will contain examples for the given
   * muscle groups. This is to help the clients with offline mode, where they are unable to make a new request to get
   * examples for the given session id.
   *
   * @param userId the user identity
   * @param sessionId the session identity
   * @param muscleGroupKeys the muscle group keys
   */
  case class UserExerciseExplicitClassificationExamples(userId: UserId, sessionId: Option[SessionId], muscleGroupKeys: Option[Seq[MuscleGroupKey]])

  /**
   * Obtain list of classification examples
   * @param sessionId the session
   * @param muscleGroupKeys the muscle group keys
   */
  private case class ExerciseExplicitClassificationExamples(sessionId: Option[SessionId], muscleGroupKeys: Option[Seq[MuscleGroupKey]])

  /**
   * Query to provide suggestions for future exercise sessions
   */
  private case object GetExerciseSuggestions

  /**
   * Query to provide suggestions for future exercise sessions
   */
  case class UserGetExerciseSuggestions(userId: UserId)

  /**
   * The id extractor
   */
  val idExtractor: ShardRegion.IdExtractor = {
    case UserExerciseExplicitClassificationExamples(userId, s, m) ⇒ (userId.toString, ExerciseExplicitClassificationExamples(s, m))
    case UserGetExerciseSuggestions(userId)                       ⇒ (userId.toString, GetExerciseSuggestions)
  }

  /**
   * The shard resolver
   */
  val shardResolver: ShardRegion.ShardResolver = {
    case UserExerciseExplicitClassificationExamples(userId, _, _) ⇒ s"${userId.hashCode() % 10}"
    case UserGetExerciseSuggestions(userId)                       ⇒ s"${userId.hashCode() % 10}"
  }

}

class UserExercisesStatistics
  extends PersistentView
  with ActorLogging {

  import scala.concurrent.duration._
  import UserExercisesStatistics._

  //Internal state
  private val userId = UserId(self.path.name)
  private var exerciseStatistics: ExerciseStatistics = ExerciseStatistics.empty
  private var suggestions = Suggestions.empty

  // we'll hang around for 360 seconds, just like the exercise sessions
  context.setReceiveTimeout(360.seconds)

  override def autoUpdateInterval: FiniteDuration = 1.second
  override def autoUpdate: Boolean = true

  override val viewId: String = s"user-exercises-statistics-${userId.toString}"
  override val persistenceId: String = s"user-exercises-${userId.toString}"

  lazy val queries: Receive = {
    case ExerciseExplicitClassificationExamples(None, None)       ⇒ sender() ! \/.right(exerciseStatistics.examples())
    case ExerciseExplicitClassificationExamples(None, Some(mgks)) ⇒ sender() ! \/.right(exerciseStatistics.examples(mgks))
    case ExerciseExplicitClassificationExamples(_, _)             ⇒ sender() ! \/.left("No examples")
    case GetExerciseSuggestions                                   ⇒ sender() ! suggestions
  }

  lazy val notExercising: Receive = {
    case SessionStartedEvt(sessionId, sessionProperties) if isPersistent ⇒
      context.become(exercising(sessionId, sessionProperties).orElse(queries))

    case ExerciseSuggestionsSetEvt(suggest) if isPersistent              ⇒
      suggestions = suggest
  }

  private def exercising(sessionId: SessionId, sessionProperties: SessionProperties): Receive = {
    case ExerciseExplicitClassificationExamples(Some(`sessionId`), _)    ⇒
      sender() ! \/.right(exerciseStatistics.examples(sessionProperties.muscleGroupKeys, sessionProperties.intendedIntensity))

    case ExerciseEvt(_, metadata, exercise) if isPersistent              ⇒
      exerciseStatistics = exerciseStatistics.withNewExercise(sessionProperties, exercise)

    case SessionEndedEvt(_) if isPersistent                              ⇒
      context.become(notExercising.orElse(queries))

    case ExerciseSuggestionsSetEvt(suggest) if isPersistent              ⇒
      suggestions = suggest
  }

  override def receive: Receive = notExercising.orElse(queries)
}
