/*
 * Copyright (C) 2009-2016 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.cache.ehcache

import javax.inject.{ Inject, Provider, Singleton }

import akka.Done
import akka.stream.Materializer
import com.google.common.primitives.Primitives
import javax.cache.{ Cache, CacheException, CacheManager, Caching }
import javax.cache.configuration.{ MutableConfiguration }

import play.api.cache._
import play.api.inject.{ ApplicationLifecycle, BindingKey, Injector, Module }
import play.api.{ Configuration, Environment }
import play.cache.{ NamedCacheImpl, AsyncCacheApi => JavaAsyncCacheApi, CacheApi => JavaCacheApi, DefaultAsyncCacheApi => DefaultJavaAsyncCacheApi, DefaultSyncCacheApi => JavaDefaultSyncCacheApi, SyncCacheApi => JavaSyncCacheApi }

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

/**
 * EhCache components for compile time injection
 */
trait EhCacheComponents {
  def environment: Environment
  def configuration: Configuration
  def applicationLifecycle: ApplicationLifecycle
  implicit def executionContext: ExecutionContext

  lazy val ehCacheManager: CacheManager = new CacheManagerProvider(environment, configuration, applicationLifecycle).get

  /**
   * Use this to create with the given name.
   */
  def cacheApi(name: String, create: Boolean = true): AsyncCacheApi = {
    val createNamedCaches = configuration.underlying.getBoolean("play.cache.createBoundCaches")
    new EhCacheApi(NamedEhCacheProvider.getNamedCache(name, ehCacheManager, createNamedCaches))
  }

  lazy val defaultCacheApi: AsyncCacheApi = cacheApi("play")
}

/**
 * EhCache implementation.
 */
class EhCacheModule extends Module {

  import scala.collection.JavaConversions._

  def bindings(environment: Environment, configuration: Configuration) = {
    val defaultCacheName = configuration.underlying.getString("play.cache.defaultCache")
    val bindCaches = configuration.underlying.getStringList("play.cache.bindCaches").toSeq
    val createBoundCaches = configuration.underlying.getBoolean("play.cache.createBoundCaches")

    // Creates a named cache qualifier
    def named(name: String): NamedCache = {
      new NamedCacheImpl(name)
    }

    // bind a cache with the given name
    def bindCache(name: String) = {
      val namedCache = named(name)
      val ehcacheKey = bind[Cache[String, CachedValue[Any]]].qualifiedWith(namedCache)
      val cacheApiKey = bind[AsyncCacheApi].qualifiedWith(namedCache)
      Seq(
        ehcacheKey.to(new NamedEhCacheProvider(name, createBoundCaches)),
        cacheApiKey.to(new NamedCacheApiProvider(ehcacheKey)),
        bind[JavaAsyncCacheApi].qualifiedWith(namedCache).to(new NamedJavaAsyncCacheApiProvider(cacheApiKey)),
        bind[Cached].qualifiedWith(namedCache).to(new NamedCachedProvider(cacheApiKey)),
        bind[SyncCacheApi].qualifiedWith(namedCache).to[DefaultSyncCacheApi],
        bind[CacheApi].qualifiedWith(namedCache).to[DefaultSyncCacheApi],
        bind[JavaCacheApi].qualifiedWith(namedCache).to[JavaDefaultSyncCacheApi],
        bind[JavaSyncCacheApi].qualifiedWith(namedCache).to[JavaDefaultSyncCacheApi]
      )
    }

    Seq(
      bind[CacheManager].toProvider[CacheManagerProvider],
      // alias the default cache to the unqualified implementation
      bind[AsyncCacheApi].to(bind[AsyncCacheApi].qualifiedWith(named(defaultCacheName))),
      bind[JavaAsyncCacheApi].to[DefaultJavaAsyncCacheApi],
      bind[SyncCacheApi].to[DefaultSyncCacheApi],
      bind[CacheApi].to[DefaultSyncCacheApi],
      bind[JavaCacheApi].to[JavaDefaultSyncCacheApi],
      bind[JavaSyncCacheApi].to[JavaDefaultSyncCacheApi]
    ) ++ bindCache(defaultCacheName) ++ bindCaches.flatMap(bindCache)
  }
}

case class CachedValue[A](value: A, expiration: Duration)

@Singleton
class CacheManagerProvider @Inject() (env: Environment, config: Configuration, lifecycle: ApplicationLifecycle) extends Provider[CacheManager] {
  lazy val get: CacheManager = {
    val resourceName = config.underlying.getString("play.cache.configResource")
    val provider = Caching.getCachingProvider
    val configUri = env.resource(resourceName).map(_.toURI).getOrElse(provider.getDefaultURI)
    val manager = provider.getCacheManager(configUri, env.classLoader) //whose default?
    lifecycle.addStopHook(() => Future.successful(manager.close()))
    manager
  }
}

private[play] class NamedEhCacheProvider(name: String, create: Boolean) extends Provider[Cache[String, CachedValue[Any]]] {
  @Inject private var manager: CacheManager = _
  lazy val get: Cache[String, CachedValue[Any]] = NamedEhCacheProvider.getNamedCache(name, manager, create)
}

private[play] object NamedEhCacheProvider {
  def getNamedCache(name: String, manager: CacheManager, create: Boolean) = try {
    if (create) {
      manager.createCache(name, new MutableConfiguration())
    }
    manager.getCache(name /*, classOf[String], classOf[CachedValue[Any]]*/ ).asInstanceOf[Cache[String, CachedValue[Any]]]
  } catch {
    case e: CacheException =>
      throw new EhCacheExistsException(
        s"""An EhCache instance with name '$name' already exists.
            |
           |This usually indicates that multiple instances of a dependent component (e.g. a Play application) have been started at the same time.
         """.stripMargin, e)
  }
}

private[play] class NamedCacheApiProvider(key: BindingKey[Cache[String, CachedValue[Any]]]) extends Provider[AsyncCacheApi] {
  @Inject private var injector: Injector = _
  lazy val get: AsyncCacheApi = {
    new EhCacheApi(injector.instanceOf(key))(injector.instanceOf[ExecutionContext])
  }
}

private[play] class NamedJavaAsyncCacheApiProvider(key: BindingKey[AsyncCacheApi]) extends Provider[JavaAsyncCacheApi] {
  @Inject private var injector: Injector = _
  lazy val get: JavaAsyncCacheApi = {
    new DefaultJavaAsyncCacheApi(injector.instanceOf(key))
  }
}

private[play] class NamedCachedProvider(key: BindingKey[AsyncCacheApi]) extends Provider[Cached] {
  @Inject private var injector: Injector = _
  lazy val get: Cached = {
    new Cached(injector.instanceOf(key))(injector.instanceOf[Materializer])
  }
}

private[play] case class EhCacheExistsException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

@Singleton
class EhCacheApi @Inject() (cache: Cache[String, CachedValue[Any]])(implicit context: ExecutionContext) extends AsyncCacheApi {

  def set(key: String, value: Any, expiration: Duration): Future[Done] = {
    Future.successful {
      cache.put(key, CachedValue(value, expiration))
      Done
    }
  }

  def get[T](key: String)(implicit ct: ClassTag[T]): Future[Option[T]] = {
    val result = Option(cache.get(key)).map(_.value).filter { v =>
      Primitives.wrap(ct.runtimeClass).isInstance(v) ||
        ct == ClassTag.Nothing || (ct == ClassTag.Unit && v == ((): Unit))
    }.asInstanceOf[Option[T]]
    Future.successful(result)
  }

  def getOrElseUpdate[A: ClassTag](key: String, expiration: Duration)(orElse: => Future[A]): Future[A] = {
    get[A](key).flatMap {
      case Some(value) => Future.successful(value)
      case None => orElse.flatMap(value => set(key, value, expiration).map(_ => value))
    }
  }

  def remove(key: String): Future[Done] = {
    Future.successful {
      cache.remove(key)
      Done
    }
  }
}

