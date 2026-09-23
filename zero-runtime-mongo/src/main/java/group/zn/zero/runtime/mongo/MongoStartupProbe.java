package group.zn.zero.runtime.mongo;

import com.mongodb.client.MongoClient;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDataHealthCheck;
import group.zn.zero.data.mongo.MongoDriverSettings;
import java.time.Duration;

/** Startup probe with a temporary client bounded by the remaining startup budget. */
final class MongoStartupProbe {
    private MongoStartupProbe() {
    }

    static void check(final MongoDriverSettings settings, final Duration timeout) {
        try (MongoClient client = new MongoDataAdapter().createClient(settings, timeout)) {
            new MongoDataHealthCheck(client, settings.databaseName()).checkOrThrow();
        }
    }
}
