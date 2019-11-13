/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2019 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package grakn.core.graph.diskstorage.configuration.backend;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import grakn.core.graph.diskstorage.configuration.ReadConfiguration;
import grakn.core.graph.diskstorage.configuration.WriteConfiguration;
import grakn.core.graph.diskstorage.util.time.Durations;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Iterator;
import java.util.List;

/**
 * {@link ReadConfiguration} wrapper for Apache Configuration
 */
public class CommonsConfiguration implements WriteConfiguration {

    private final Configuration config;

    private static final Logger LOG = LoggerFactory.getLogger(CommonsConfiguration.class);

    public CommonsConfiguration() {
        this(new BaseConfiguration());
    }

    public CommonsConfiguration(Configuration config) {
        this.config = Preconditions.checkNotNull(config);
    }

    public Configuration getCommonConfiguration() {
        return config;
    }

    @Override
    public <O> O get(String key, Class<O> dataType) {
        if (!config.containsKey(key)) return null;

        if (dataType.isArray()) {
            Preconditions.checkArgument(dataType.getComponentType() == String.class, "Only string arrays are supported: %s", dataType);
            return (O) config.getStringArray(key);
        } else if (Number.class.isAssignableFrom(dataType)) {
            // A properties file configuration returns Strings even for numeric
            // values small enough to fit inside Integer (e.g. 5000). In-memory
            // configuration implementations seem to be able to store and return actual
            // numeric types rather than String
            //
            // We try to handle either case here
            Object o = config.getProperty(key);
            if (dataType.isInstance(o)) {
                return (O) o;
            } else {
                return constructFromStringArgument(dataType, o.toString());
            }
        } else if (dataType == String.class) {
            return (O) config.getString(key);
        } else if (dataType == Boolean.class) {
            return (O) Boolean.valueOf(config.getBoolean(key));
        } else if (dataType.isEnum()) {
            Enum[] constants = (Enum[]) dataType.getEnumConstants();
            Preconditions.checkState(null != constants && 0 < constants.length, "Zero-length or undefined enum");

            String enumString = config.getProperty(key).toString();
            for (Enum ec : constants) {
                if (ec.toString().equals(enumString)) {
                    return (O) ec;
                }
            }
            throw new IllegalArgumentException("No match for string \"" + enumString + "\" in enum " + dataType);
        } else if (dataType == Object.class) {
            return (O) config.getProperty(key);
        } else if (Duration.class.isAssignableFrom(dataType)) {
            // This is a conceptual leak; the config layer should ideally only handle standard library types
            Object o = config.getProperty(key);
            if (o instanceof Duration) {
                return (O) o;
            } else {
                String[] comps = o.toString().split("\\s");
                final TemporalUnit unit;
                switch (comps.length) {
                    case 1:
                        //By default, times are in milli seconds
                        unit = ChronoUnit.MILLIS;
                        break;
                    case 2:
                        unit = Durations.parse(comps[1]);
                        break;
                    default:
                        throw new IllegalArgumentException("Cannot parse time duration from: " + o.toString());
                }
                return (O) Duration.of(Long.valueOf(comps[0]), unit);
            }
            // Lists are deliberately not supported.  List's generic parameter
            // is subject to erasure and can't be checked at runtime.  Someone
            // could create a ConfigOption<List<Number>>; we would instead return
            // a List<String> like we always do at runtime, and it wouldn't break
            // until the client tried to use the contents of the list.
            //
            // We could theoretically get around this by adding a type token to
            // every declaration of a List-typed ConfigOption, but it's just
            // not worth doing since we only actually use String[] anyway.
//        } else if (List.class.isAssignableFrom(dataType)) {
//            return (O) config.getProperty(key);
        } else throw new IllegalArgumentException("Unsupported data type: " + dataType);
    }

    private <O> O constructFromStringArgument(Class<O> dataType, String arg) {
        try {
            Constructor<O> ctor = dataType.getConstructor(String.class);
            return ctor.newInstance(arg);
            // ReflectiveOperationException is narrower and more appropriate than Exception, but only @since 1.7
            //} catch (ReflectiveOperationException e) {
        } catch (Exception e) {
            LOG.error("Failed to parse configuration string \"{}\" into type {} due to the following reflection exception", arg, dataType, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Iterable<String> getKeys(String prefix) {
        List<String> result = Lists.newArrayList();
        Iterator<String> keys;
        if (StringUtils.isNotBlank(prefix)) keys = config.getKeys(prefix);
        else keys = config.getKeys();
        while (keys.hasNext()) result.add(keys.next());
        return result;
    }

    @Override
    public void close() {
        //Do nothing
    }

    @Override
    public <O> void set(String key, O value) {
        if (value == null) {
            config.clearProperty(key);
        } else if (Duration.class.isAssignableFrom(value.getClass())) {
            config.setProperty(key, ((Duration) value).toMillis());
        } else {
            config.setProperty(key, value);
        }
    }

    @Override
    public void remove(String key) {
        config.clearProperty(key);
    }

    @Override
    public WriteConfiguration copy() {
        BaseConfiguration copy = new BaseConfiguration();
        copy.copy(config);
        return new CommonsConfiguration(copy);
    }

}