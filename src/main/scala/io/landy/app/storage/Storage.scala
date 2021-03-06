package io.landy.app.storage

import akka.pattern.pipe
import com.typesafe.config.{Config, ConfigFactory}
import io.landy.app.actors.ExecutingActor
import io.landy.app.instance.Instance.{Id, State}
import io.landy.app.instance._
import io.landy.app.ml._
import io.landy.app.model._
import io.landy.app.util.Logging
import org.apache.commons.lang.StringUtils
import reactivemongo.bson
import reactivemongo.bson.DefaultBSONHandlers

import scala.concurrent.Future
import scala.pickling.Unpickler
import scala.pickling.json._
import scala.util.{Failure, Success}

import scala.language.implicitConversions
import scala.language.existentials

class StorageActor extends ExecutingActor {

  import Storage._
  import reactivemongo.api._
  import reactivemongo.api.collections.bson._
  import reactivemongo.bson._

  private val config: Config = ConfigFactory.load()

  private val nodes: Seq[String]  = config.getString("landy.mongo.hosts").split(",").toSeq.map { s => s.trim }

  private val driver: MongoDriver = new MongoDriver()

  private val connection: MongoConnection = driver.connection(nodes = nodes)

  import Commands._

  private def trace(f: Future[_], reason: String, args: Any*) = {
    import Logging.extendLogging
    f.andThen {
      case Success(r: Traceable) =>
        r.trace match { case (t, _args) => log.x.debug(t, _args:_*) }
      case Failure(t) =>
        log.x.error(t, reason, args)
    }
  }

  /**
    * Looks up particular collection inside database
    *
    * @param collection collection to bee sought for
    * @return found collection
    */
  private def find(db: String)(collection: String): BSONCollection =
    connection.db(name = db).collection[BSONCollection](collection)

  def receive = trace {

    case LoadRequest(persister, selector, upTo) =>
      implicit val reader = persister

      // TODO(kudinkin): `collect` silently swallows the errors in the `Storage`

      val c = find(persister.database)(persister.collection)
      val r = c .find(selector)
                .sort(BSONDocument(`_id` -> -1))
                .cursor[persister.Target](ReadPreference.Nearest(filterTag = None))
                .collect[List](upTo, stopOnError = false)

      trace(
        r.map { os => Commands.LoadResponse(os) },
        s"Failed to retrieve data from ${persister.collection} for request: ${selector}"
      ) pipeTo sender()


    case CountRequest(persister, selector) =>
      val c = find(persister.database)(persister.collection)
      val r = c.count(selector = Some(selector))

      trace(
        r.map { o => Commands.CountResponse(o) },
        s"Failed to retrieve data from ${persister.collection} for request: ${selector}"
      ) pipeTo sender()

    case req @ StoreRequest(persister, obj) =>
      val c = find(persister.database)(persister.collection)
      val r = c.insert(obj)(writer = persister, ec = executionContext)

      trace(
        r.map { r => Commands.StoreResponse(r.ok, req) },
        s"Failed to save data in ${persister.collection} for object: ${obj}"
      ) pipeTo sender()

    case req @ UpdateRequest(persister, selector, modifier) =>
      val w = Storage.BSONDocumentIdentity

      val c = find(persister.database)(persister.collection)
      val r = c.update(selector, BSONDocument("$set" -> modifier))(selectorWriter = w, updateWriter = w, ec = executionContext)

      trace(
        r.map { res => Commands.UpdateResponse(res.ok, req) },
        s"Failed to update data in ${persister.collection} with ${modifier} for object: ${selector}"
      ) pipeTo sender()

  }
}

object Storage extends DefaultBSONHandlers {

  val actorName: String = "storage"

  type Id = String

  val `_id` = "_id"
  val `$oid` = "$oid"

  import reactivemongo.bson._

  /**
    * Helper generating selector for Mongo queries by supplied id of the object
    *
    * @param appId object-id
    * @return bson-document (so called 'selector')
    */
  private def byId(appId: Instance.Id) = {
    import Persisters.instanceIdPersister
    BSONDocument(`_id` -> appId)
  }

  /**
    * Helper padding id with zero to become BSON-compliant
    * @param id to be padded
    * @return padded id
    */
  def padId(id: String): String = id match {
    case _ => StringUtils.leftPad(id, 24, '0')
  }

  /**
    * Commands accepted by the storaging actor
    */
  object Commands {

    /**
      * Marker-trait to designate messages (or responses) of particular interest
      */
    trait Traceable {
      def trace: (String, Seq[Any])
    }

    case class StoreRequest[T](persister: PersisterW[T], obj: T) extends Instance.AutoStartMessage[StoreResponse]
    case class StoreResponse(ok: Boolean, req: StoreRequest[_]) extends Traceable {
      def trace =
        ("Stored '{}' ({}) instance / {{}}", Seq(req.obj.getClass.getName, req.obj.hashCode(), req.obj))
    }

    object Store {
      def apply[T](obj: T)(implicit persister: PersisterW[T]): StoreRequest[T] = StoreRequest[T](persister, obj)
    }

    case class LoadRequest  [T](persister: PersisterR[T], selector: BSONDocument, maxSize: Int)
    case class LoadResponse [T](seq: Seq[T])

    object Load {

      def apply[T](id: Instance.Id)(implicit persister: PersisterR[T]) =
        LoadRequest[T](persister, byId(id), 1)

      def apply[T](selector: Producer[(String, BSONValue)]*)(maxSize: Int = Int.MaxValue)(implicit persister: PersisterR[T]) =
        LoadRequest[T](persister, BSONDocument(selector:_*), maxSize)

    }

    case class UpdateRequest[T](persister: PersisterBase[T], selector: BSONDocument, modifier: BSONDocument)
    case class UpdateResponse(ok: Boolean, req: UpdateRequest[_]) extends Traceable {
      def trace =
        ("Updated {{}} instance / {{}}", Seq(req.selector, req.modifier))
    }

    object Update {

      def apply[T](selector: Producer[(String, BSONValue)]*)(modifier: Producer[(String, BSONValue)]*)(implicit persister: PersisterBase[T]) =
        UpdateRequest[T](
          persister = persister,
          selector = BSONDocument(selector:_*),
          modifier = BSONDocument(modifier:_*)
        )

      def apply[T](id: Instance.Id)(modifier: Producer[(String, BSONValue)]*)(implicit persister: PersisterBase[T]) =
        UpdateRequest[T](
          persister = persister,
          selector = byId(id),
          modifier = BSONDocument(modifier:_*)
        )
    }

    case class CountRequest[T](persister: PersisterBase[T], selector: BSONDocument)
    case class CountResponse(count: Int)

    object Count {
      def apply[T](elements: Producer[(String, BSONValue)]*)(implicit persister: PersisterBase[T]) = CountRequest[T](
        persister = persister,
        selector = BSONDocument(elements:_*)
      )
    }

  }


  /**
    * Persisters
    */

  trait PersisterBase[T] {
    type Target = T

    val collection: String
    val database: String
  }

  trait PersisterR[T] extends PersisterBase[T] with BSONDocumentReader[T] {
    override def toString: String = s"persister-r($collection)"
  }

  trait PersisterW[T] extends PersisterBase[T] with BSONDocumentWriter[T] {
    override def toString: String = s"persister-w($collection)"
  }

  trait Persister[T] extends PersisterR[T] with PersisterW[T] {
    protected val c: Config = ConfigFactory.load()
    override def toString: String = s"persister-rw($collection)"
  }

  object Persisters {

    import reactivemongo.bson._

    /**
      * Helpers facilitating collections marshalling/unmarshalling
      */
    object Helpers {
      implicit def MapReader[B <: BSONValue, V](implicit reader: BSONReader[B, V]): BSONDocumentReader[Map[String, V]] =
        new BSONDocumentReader[Map[String, V]] {
          def read(bson: BSONDocument): Map[String, V] = bson.elements.map { case (k, v) => k -> reader.read(v.asInstanceOf[B]) }.toMap
        }

      implicit def MapWriter[V, B <: BSONValue](implicit writer: BSONWriter[V, B]): BSONDocumentWriter[Map[String, V]] =
        new BSONDocumentWriter[Map[String, V]] {
          def write(map: Map[String, V]): BSONDocument = BSONDocument(map.toStream.map { case (k, v) => k -> writer.write(v) })
        }

      implicit def SeqReader[B <: BSONValue, V](implicit reader: BSONReader[B, V]): BSONReader[BSONArray, Seq[V]] =
        new BSONReader[BSONArray, Seq[V]] {
          def read(bson: BSONArray): Seq[V] = bson.values.map { case v => reader.read(v.asInstanceOf[B]) }.toSeq
        }

      implicit def SeqWriter[V, B <: BSONValue](implicit writer: BSONWriter[V, B]): BSONWriter[Seq[V], BSONArray] =
        new BSONWriter[Seq[V], BSONArray] {
          def write(seq: Seq[V]): BSONArray = BSONArray(seq.toStream.map { case (v) => writer.write(v) })
        }
    }

    /**
      * Application model persisters instances
      */

    import Helpers._

    // TODO(kudinkin): Move persisters closer to actual classes they serialize

    implicit val userIdentityPersister =
      new BSONDocumentReader[UserIdentity] with BSONDocumentWriter[UserIdentity] {

        override def write(uid: UserIdentity): BSONDocument =
          BSON.writeDocument(uid.params)

        override def read(bson: BSONDocument): UserIdentity =
          bson.asOpt[UserIdentity.Params] collect { case ps => UserIdentity(ps) } getOrElse UserIdentity.empty
      }

    implicit val instanceIdPersister =
      new BSONReader[BSONObjectID, Instance.Id] with BSONWriter[Instance.Id, BSONObjectID] {
        override def write(d: Instance.Id): BSONObjectID = BSONObjectID(d.value)
        override def read(bson: BSONObjectID): Instance.Id = Instance.Id(bson.stringify)
      }

    implicit val variationIdPersister =
      new BSONReader[BSONString, Variation.Id] with BSONWriter[Variation.Id, BSONString] {
        override def write(d: Variation.Id): BSONString = BSONString(d.value)
        override def read(bson: BSONString): Variation.Id = Variation.Id(bson.value)
      }

    implicit val variationPersister =
      new BSONDocumentReader[Variation] with BSONDocumentWriter[Variation] {

        import Variation._

        override def write(v: Variation): BSONDocument =
          BSONDocument(
            `value` -> v.value,
            `id`    -> v.id
          )

        override def read(bson: BSONDocument): Variation =
          Variation(
            id    = bson.getAs[Variation.Id]    (`id`)    .get,
            value = bson.getAs[Variation.Type]  (`value`) .get
          )
      }

    implicit val userDataDescriptorPersister =
      new BSONDocumentReader[UserDataDescriptor] with BSONDocumentWriter[UserDataDescriptor] {

        override def write(d: UserDataDescriptor): BSONDocument =
          BSONDocument(
            UserDataDescriptor.`name`         -> d.name,
            UserDataDescriptor.`categorical`  -> d.categorical
          )

        override def read(bson: BSONDocument): UserDataDescriptor =
          UserDataDescriptor(
            bson.getAs[String]  (UserDataDescriptor.`name`)         .get,
            bson.getAs[Boolean] (UserDataDescriptor.`categorical`)  .get
          )
      }

    implicit val startEventPersister =
      new Persister[StartEvent] {
        import Event._

        override val database:    String = c.getString("landy.mongo.database.events")
        override val collection:  String = `type:Start`

        override def write(t: StartEvent): BSONDocument =
          BSONDocument(
            `appId`     -> t.appId,
            `timestamp` -> t.timestamp,
            `session`   -> t.session,
            `identity`  -> t.identity,
            `variation` -> t.variation,
            `kind`      -> t.kind.toString
          )

        override def read(bson: BSONDocument): StartEvent =
          StartEvent(
            appId     = bson.getAs[Instance.Id]   (`appId`)     .get,
            session   = bson.getAs[String]        (`session`)   .get,
            timestamp = bson.getAs[Long]          (`timestamp`) .get,
            identity  = bson.getAs[UserIdentity]  (`identity`)  .get,
            variation = bson.getAs[Variation.Id]  (`variation`) .get,
            kind      = bson.getAs[String]        (`kind`)      .map { StartEvent.Kind.withName }
                                                                .get
          )
      }

    implicit val finishEventPersister =
      new Persister[FinishEvent] {
        import Event._

        override val database: String   = c.getString("landy.mongo.database.events")
        override val collection: String = `type:Finish`

        override def write(t: FinishEvent) =
          BSONDocument(
            `appId`     -> t.appId,
            `timestamp` -> t.timestamp,
            `session`   -> t.session
          )

        override def read(bson: BSONDocument) =
          FinishEvent(
            appId     = bson.getAs[Instance.Id] (`appId`)     .get,
            session   = bson.getAs[String]      (`session`)   .get,
            timestamp = bson.getAs[Long]        (`timestamp`) .get
          )
      }


    object Model {

      implicit val binaryPersister =
        new BSONReader[BSONBinary, Instance.Config.Model] with BSONWriter[Instance.Config.Model, BSONBinary] {

          import scala.pickling.binary._

          override def write(model: Instance.Config.Model): BSONBinary =
            BSONBinary(
              model match {
                case Left(e)  => Models.Picklers.pickle(e.asInstanceOf[PickleableModel]).value
                case Right(e) => Models.Picklers.pickle(e.asInstanceOf[PickleableModel]).value
              },
              Subtype.UserDefinedSubtype
            )

          override def read(bson: BSONBinary): Instance.Config.Model =
            Models.Picklers.unpickle(bson.byteArray) match {
              case c: SparkClassificationModel  [SparkModel.Model] => Left(c)
              case r: SparkRegressionModel      [SparkModel.Model] => Right(r)
              case x =>
                throw new UnsupportedOperationException()
            }
        }

      implicit val jsonPersister =
          new BSONReader[BSONString, Instance.Config.Model] with BSONWriter[Instance.Config.Model, BSONString] {

          import scala.pickling.json._

          override def write(model: Instance.Config.Model): BSONString =
            BSONString(
              model match {
                case Left(e)  => Models.Picklers.pickle(e.asInstanceOf[PickleableModel]).value
                case Right(e) => Models.Picklers.pickle(e.asInstanceOf[PickleableModel]).value
              }
            )

          override def read(bson: BSONString): Instance.Config.Model =
            Models.Picklers.unpickle(bson.value) match {
              case c: SparkClassificationModel  [SparkModel.Model] => Left(c)
              case r: SparkRegressionModel      [SparkModel.Model] => Right(r)
              case x =>
                throw new UnsupportedOperationException()
            }
        }
    }

    implicit val instanceStatePersister =
      new BSONDocumentReader[Instance.State] with BSONDocumentWriter[Instance.State] {

        override def write(s: State): BSONDocument =
          s match {
            case State.Training
               | State.Suspended
               | State.NoData =>
              BSONDocument(
                State.`name` -> s.typeName
              )

            case State.Predicting(from) =>
              BSONDocument(
                State.`name`            -> State.Predicting.typeName,
                State.Predicting.`from` -> from.ts
              )
          }

        override def read(bson: BSONDocument): State = {
          import Instance.Epoch

          bson.getAs[String](State.`name`).collect {
            case State.Predicting.typeName =>
              State.Predicting(
                bson.getAs[Long](State.Predicting.`from`) map { ts => Epoch(ts) } getOrElse Epoch.anteChristum
              )

            case n: String =>
              State.withName(n)
          }.get
        }
      }

    //
    // TODO(kudinkin): Merge
    //

    implicit val instanceConfigPersister =
      new BSONDocumentReader[Instance.Config] with BSONDocumentWriter[Instance.Config] {
        import Instance.Config._
        import Model.binaryPersister

        override def write(c: Instance.Config): BSONDocument = {
          BSONDocument(
            `variations`  -> c.variations,
            `descriptors` -> c.userDataDescriptors,
            `model`       -> c.model
          )
        }

        override def read(bson: BSONDocument): Instance.Config = {
          { for (
              vs    <- bson.getAs[Seq[Variation]]           (`variations`);
              ds    <- bson.getAs[Seq[UserDataDescriptor]]  (`descriptors`)
            ) yield Instance.Config(
                variations          = vs.toList,
                userDataDescriptors = ds.toList,
                model               = bson.getAs[Instance.Config.Model](`model`)
              )
          }.get
        }
      }

    implicit val instanceRecordPersister =
      new Persister[Instance.Record] {
        import Instance.Record._

        override val database:    String  = c.getString("landy.mongo.database.master")
        override val collection:  String  = "instances"

        override def write(t: Instance.Record): BSONDocument =
          BSONDocument(
            `_id`       -> t.appId,
            `runState`  -> t.runState,
            `config`    -> t.config
          )

        override def read(bson: BSONDocument): Instance.Record =
        { for (
            id  <- bson.getAs[Instance.Id]      (`_id`);
            s   <- bson.getAs[State]            (`runState`);
            c   <- bson.getAs[Instance.Config]  (`config`)
          ) yield Instance.Record(
            appId     = id,
            runState  = s,
            config    = c
          )
        }.get


      }

  }
}