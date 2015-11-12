package com.flp.control.service.serialization

import com.flp.control.instance.{AppInstanceConfig, AppInstanceStatus}
import com.flp.control.model._
import spray.httpx.SprayJsonSupport
import spray.json._

trait JsonSerialization extends DefaultJsonProtocol with SprayJsonSupport {

  private def field[T](value: JsValue, fieldName: String, default: => T)(implicit reader: JsonReader[Option[T]]): T = {
    fromField[Option[T]](value, fieldName)(reader = reader).getOrElse(default)
  }

  private def field[T](value: JsValue, fieldName: String)(implicit reader: JsonReader[Option[T]]): T = {
    fromField[Option[T]](value, fieldName)(reader = reader).get
  }

  /**
    * `UserIdentity`
    */
  private[service] implicit object UserIdentityJsonFormat extends RootJsonFormat[UserIdentity] {

    override def write(o: UserIdentity): JsValue = o.params.toJson

    override def read(value: JsValue): UserIdentity = UserIdentity(value.convertTo[UserIdentity.Params])
  }

  /**
    * `Variation`
    */
  private[service] implicit object VariationJsonFormat extends RootJsonFormat[Variation] {

    override def write(o: Variation): JsValue = o.id.toJson

    override def read(value: JsValue): Variation = Variation(value.convertTo[Variation.Id])
  }

  /**
    * `AppInstanceConfig`
    */
  private[service] implicit object AppInstanceConfigJsonFormat extends RootJsonFormat[AppInstanceConfig] {
    def write(config: AppInstanceConfig): JsValue = config.variations.toJson
    def read(value: JsValue): AppInstanceConfig = AppInstanceConfig(value.convertTo[Seq[Variation]])
  }

  /**
   * `AppInstanceStatus`
   */
  private[service] implicit object AppInstanceStatusJsonFormat extends RootJsonFormat[AppInstanceStatus] {
    def write(status: AppInstanceStatus): JsObject =
      JsObject(
        "runState" -> JsString(status.runState.toString),
        "events" -> JsObject(
          "all" -> JsObject(
            "start" -> JsNumber(status.eventsAllStart),
            "finish" -> JsNumber(status.eventsAllFinish)
          ),
          "learn" -> JsObject(
            "start" -> JsNumber(status.eventsLearnStart),
            "finish" -> JsNumber(status.eventsLearnFinish)
          )

        )
      )

    def read(value: JsValue): AppInstanceStatus =
      AppInstanceStatus.empty
  }

  /**
    * `StartEvent`
    */
  private[service] implicit object StartEventJsonFormat extends RootJsonFormat[StartEvent] {
    import Event._

    def write(event: StartEvent): JsObject =
      JsObject(
        `type`      -> JsString("start"),
        `session`   -> JsString(event.session),
        `timestamp` -> JsNumber(event.timestamp),
        `identity`  -> event.identity.toJson,
        `variation` -> event.variation.toJson
      )

    def read(value: JsValue): StartEvent =
      StartEvent(
        session   = field[String]       (value, `session`,    ""),
        timestamp = field[Long]         (value, `timestamp`,  0l),
        identity  = field[UserIdentity] (value, `identity`,   UserIdentity.empty),
        variation = field[Variation]    (value, `variation`,  Variation.sentinel)
      )
  }

  /**
    * `PredictEvent`
    */
  private[service] implicit object PredictEventJsonFormat extends RootJsonFormat[PredictEvent] {
    import Event._

    def write(event: PredictEvent): JsObject = JsObject(
      `type` -> JsString("start"),
      `session` -> JsString(event.session),
      `timestamp` -> JsNumber(event.timestamp),
      `identity` -> event.identity.toJson
    )

    def read(value: JsValue): PredictEvent = PredictEvent(
      session   = field[String]       (value, `session`,    ""),
      timestamp = field[Long]         (value, `timestamp`,  0l),
      identity  = field[UserIdentity] (value, `identity`,   UserIdentity.empty )
    )
  }

  /**
    * `FinishEvent`
    */
  private[service] implicit object FinishEventJsonFormat extends RootJsonFormat[FinishEvent] {
    import Event._

    def write(event: FinishEvent): JsObject = JsObject(
      `type` -> JsString("finish"),
      `session` -> JsString(event.session),
      `timestamp` -> JsNumber(event.timestamp)
    )

    def read(value: JsValue): FinishEvent = FinishEvent(
      session = field[String](value, `session`, ""),
      timestamp = field[Long](value, `timestamp`, 0l)
    )
  }
}
