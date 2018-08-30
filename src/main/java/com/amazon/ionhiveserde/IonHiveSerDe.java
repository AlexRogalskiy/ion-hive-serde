/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at:
 *
 *      http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.amazon.ionhiveserde;

import com.amazon.ionhiveserde.objectinspectors.factories.IonObjectInspectorFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nullable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.AbstractSerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeStats;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import software.amazon.ion.IonDatagram;
import software.amazon.ion.IonSystem;
import software.amazon.ion.IonType;
import software.amazon.ion.IonValue;
import software.amazon.ion.IonWriter;
import software.amazon.ion.system.IonSystemBuilder;

/**
 * <p>
 * Hive SerDe for the <a href="http://amzn.github.io/ion-docs/docs.html">Amazon Ion</a> data format.
 * </p>
 * <p>
 * For more information on Hive SerDes see <a href="https://cwiki.apache.org/confluence/display/Hive/SerDe">wiki</a>.
 * </p>
 */
public class IonHiveSerDe extends AbstractSerDe {

    private IonSystem ion;

    private ObjectInspector objectInspector;
    private IonObjectInspectorFactory objectInspectorFactory;

    private Class<? extends Writable> serializedClass;

    private SerDeStats stats;

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("deprecation") // we are forced to override this constructor even though it's deprecated
    public void initialize(final @Nullable Configuration conf, final Properties tbl) throws SerDeException {
        stats = new SerDeStats();
        final StructTypeInfo tableInfo = (StructTypeInfo) TypeInfoFactory
            .getStructTypeInfo(readColumnNames(tbl), readColumnTypes(tbl));

        ion = buildIonSystem(conf);

        objectInspectorFactory = new IonObjectInspectorFactory(tableInfo);
        objectInspector = objectInspectorFactory.objectInspectorFor(tableInfo);

        serializedClass = Text.class; // TODO pick from config
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<? extends Writable> getSerializedClass() {
        return serializedClass;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Writable serialize(final Object obj, final ObjectInspector objectInspector) throws SerDeException {
        final StringBuilder out = new StringBuilder();
        final StructObjectInspector structObjectInspector = (StructObjectInspector) objectInspector;

        try (IonWriter writer = ion.newTextWriter(out)) {
            writer.stepIn(IonType.STRUCT);

            for (StructField field : structObjectInspector.getAllStructFieldRefs()) {
                writer.setFieldName(field.getFieldName());

                final Object fieldData = structObjectInspector.getStructFieldData(obj, field);
                serializeFieldData(writer, fieldData, field.getFieldObjectInspector());
            }

            writer.stepOut();
        } catch (IOException e) {
            throw new SerDeException(e);
        }

        // TODO binary or text from config
        return new Text(out.toString());
    }

    private void serializeFieldData(final IonWriter writer,
                                    final Object fieldData,
                                    final ObjectInspector objectInspector) throws IOException, SerDeException {
        if (fieldData == null) {
            return;
        }

        if (objectInspector.getCategory() == ObjectInspector.Category.PRIMITIVE) {
            final PrimitiveObjectInspector primitiveObjectInspector = (PrimitiveObjectInspector) objectInspector;
            switch (primitiveObjectInspector.getPrimitiveCategory()) {
                case VOID:
                case UNKNOWN:
                    throw new SerDeException("Unknown primitive");
            }
        }

        final IonValue ionValue = (IonValue) fieldData;
        ionValue.writeTo(writer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SerDeStats getSerDeStats() {
        return stats;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object deserialize(final Writable blob) throws SerDeException {
        IonValue value;

        if (blob instanceof Text) {
            final Text text = (Text) blob;

            stats.setRawDataSize(text.getBytes().length);

            final String rawText = text.toString().trim();
            final IonDatagram datagram = ion.getLoader().load(rawText);

            if (datagram.size() != 1) {
                throw new IllegalStateException("There cannot be more than one ion value");
            }

            value = datagram.get(0);
        } else if (blob instanceof ByteWritable) {
            throw new UnsupportedOperationException("TODO not implemented");
        } else {
            throw new UnsupportedOperationException("TODO not implemented");
        }

        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ObjectInspector getObjectInspector() throws SerDeException {
        return objectInspector;
    }

    private List<String> readColumnNames(final Properties tbl) {
        final String columnNameProperty = tbl.getProperty(serdeConstants.LIST_COLUMNS);

        if (columnNameProperty.isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.asList(columnNameProperty.split(","));
    }

    private List<TypeInfo> readColumnTypes(final Properties tbl) {
        final String columnTypeProperty = tbl.getProperty(serdeConstants.LIST_COLUMN_TYPES);

        if (columnTypeProperty.isEmpty()) {
            return new ArrayList<>();
        }

        return TypeInfoUtils.getTypeInfosFromTypeString(columnTypeProperty);
    }

    private IonSystem buildIonSystem(final @Nullable Configuration conf) {
        // TODO configure it from SERDEPROPERTIES

        return IonSystemBuilder.standard().build();
    }
}
