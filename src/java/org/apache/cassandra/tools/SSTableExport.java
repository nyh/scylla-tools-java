/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.commons.cli.*;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.*;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;

import static org.apache.cassandra.utils.ByteBufferUtil.bytesToHex;
import static org.apache.cassandra.utils.ByteBufferUtil.hexToBytes;

/**
 * Export SSTables to JSON format.
 */
public class SSTableExport
{
    private static final ObjectMapper jsonMapper = new ObjectMapper();

    private static final String KEY_OPTION = "k";
    private static final String EXCLUDEKEY_OPTION = "x";
    private static final String ENUMERATEKEYS_OPTION = "e";

    private static final Options options = new Options();
    private static CommandLine cmd;

    static
    {
        Option optKey = new Option(KEY_OPTION, true, "Row key");
        // Number of times -k <key> can be passed on the command line.
        optKey.setArgs(500);
        options.addOption(optKey);

        Option excludeKey = new Option(EXCLUDEKEY_OPTION, true, "Excluded row key");
        // Number of times -x <key> can be passed on the command line.
        excludeKey.setArgs(500);
        options.addOption(excludeKey);

        Option optEnumerate = new Option(ENUMERATEKEYS_OPTION, false, "enumerate keys only");
        options.addOption(optEnumerate);

        // disabling auto close of the stream
        jsonMapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
    }

    /**
     * Checks if PrintStream error and throw exception
     *
     * @param out The PrintStream to be check
     */
    private static void checkStream(PrintStream out) throws IOException
    {
        if (out.checkError())
            throw new IOException("Error writing output stream");
    }

    /**
     * JSON Hash Key serializer
     *
     * @param out   The output steam to write data
     * @param value value to set as a key
     */
    private static void writeKey(PrintStream out, String value)
    {
        writeJSON(out, value);
        out.print(": ");
    }

    /**
     * JSON ColumnFamily metadata serializer.</br> Serializes:
     * <ul>
     * <li>column family deletion info (if present)</li>
     * </ul>
     *
     * @param out The output steam to write data
     * @param cf  to which the metadata belongs
     */
    private static void writeMeta(PrintStream out, ColumnFamily cf)
    {
        if (!cf.deletionInfo().equals(DeletionInfo.LIVE))
        {
            // begin meta
            writeKey(out, "metadata");
            writeDeletionInfo(out, cf.deletionInfo().getTopLevelDeletion());
            out.print(",");
        }
    }

    private static void writeDeletionInfo(PrintStream out, DeletionTime deletionTime)
    {
        out.print("{");
        writeKey(out, "deletionInfo");
        // only store topLevelDeletion (serializeForSSTable only uses this)
        writeJSON(out, deletionTime);
        out.print("}");
    }

    /**
     * Serialize columns using given column iterator
     *
     * @param atoms      column iterator
     * @param out        output stream
     * @param comparator columns comparator
     * @param cfMetaData Column Family metadata (to get validator)
     */
    private static void serializeAtoms(Iterator<OnDiskAtom> atoms, PrintStream out, AbstractType<?> comparator, CFMetaData cfMetaData)
    {
        while (atoms.hasNext())
        {
            writeJSON(out, serializeAtom(atoms.next(), comparator, cfMetaData));

            if (atoms.hasNext())
                out.print(", ");
        }
    }

    private static List<Object> serializeAtom(OnDiskAtom atom, AbstractType<?> comparator, CFMetaData cfMetaData)
    {
        if (atom instanceof Column)
        {
            return serializeColumn((Column) atom, comparator, cfMetaData);
        }
        else
        {
            assert atom instanceof RangeTombstone;
            RangeTombstone rt = (RangeTombstone) atom;
            ArrayList<Object> serializedColumn = new ArrayList<Object>();
            serializedColumn.add(comparator.getString(rt.min));
            serializedColumn.add(comparator.getString(rt.max));
            serializedColumn.add(rt.data.markedForDeleteAt);
            serializedColumn.add("t");
            serializedColumn.add(rt.data.localDeletionTime);
            return serializedColumn;
        }
    }

    /**
     * Serialize a given column to the JSON format
     *
     * @param column     column presentation
     * @param comparator columns comparator
     * @param cfMetaData Column Family metadata (to get validator)
     * @return column as serialized list
     */
    private static List<Object> serializeColumn(Column column, AbstractType<?> comparator, CFMetaData cfMetaData)
    {
        ArrayList<Object> serializedColumn = new ArrayList<Object>();

        ByteBuffer name = ByteBufferUtil.clone(column.name());
        ByteBuffer value = ByteBufferUtil.clone(column.value());

        serializedColumn.add(comparator.getString(name));
        if (column instanceof DeletedColumn)
        {
            serializedColumn.add(ByteBufferUtil.bytesToHex(value));
        }
        else
        {
            AbstractType<?> validator = cfMetaData.getValueValidator(cfMetaData.getColumnDefinitionFromColumnName(name));
            serializedColumn.add(validator.getString(value));
        }
        serializedColumn.add(column.timestamp());

        if (column instanceof DeletedColumn)
        {
            serializedColumn.add("d");
        }
        else if (column instanceof ExpiringColumn)
        {
            serializedColumn.add("e");
            serializedColumn.add(((ExpiringColumn) column).getTimeToLive());
            serializedColumn.add(column.getLocalDeletionTime());
        }
        else if (column instanceof CounterColumn)
        {
            serializedColumn.add("c");
            serializedColumn.add(((CounterColumn) column).timestampOfLastDelete());
        }

        return serializedColumn;
    }

    /**
     * Get portion of the columns and serialize in loop while not more columns left in the row
     *
     * @param row SSTableIdentityIterator row representation with Column Family
     * @param key Decorated Key for the required row
     * @param out output stream
     */
    private static void serializeRow(SSTableIdentityIterator row, DecoratedKey key, PrintStream out)
    {
        ColumnFamily columnFamily = row.getColumnFamily();
        CFMetaData cfMetaData = columnFamily.metadata();
        AbstractType<?> comparator = columnFamily.getComparator();

        out.print("{");
        writeKey(out, "key");
        writeJSON(out, bytesToHex(key.key));
        out.print(",");

        writeMeta(out, columnFamily);

        writeKey(out, "columns");
        out.print("[");

        serializeAtoms(row, out, comparator, cfMetaData);

        out.print("]");
        out.print("}");
    }

    /**
     * Enumerate row keys from an SSTableReader and write the result to a PrintStream.
     *
     * @param desc the descriptor of the file to export the rows from
     * @param outs PrintStream to write the output to
     * @throws IOException on failure to read/write input/output
     */
    public static void enumeratekeys(Descriptor desc, PrintStream outs)
    throws IOException
    {
        KeyIterator iter = new KeyIterator(desc);
        DecoratedKey lastKey = null;
        while (iter.hasNext())
        {
            DecoratedKey key = iter.next();

            // validate order of the keys in the sstable
            if (lastKey != null && lastKey.compareTo(key) > 0)
                throw new IOException("Key out of order! " + lastKey + " > " + key);
            lastKey = key;

            outs.println(bytesToHex(key.key));
            checkStream(outs); // flushes
        }
        iter.close();
    }

    /**
     * Export specific rows from an SSTable and write the resulting JSON to a PrintStream.
     *
     * @param desc     the descriptor of the sstable table to read from
     * @param outs     PrintStream to write the output to
     * @param toExport the keys corresponding to the rows to export
     * @param excludes keys to exclude from export
     * @throws IOException on failure to read/write input/output
     */
    public static void export(Descriptor desc, PrintStream outs, Collection<String> toExport, String[] excludes) throws IOException
    {
        SSTableReader reader = SSTableReader.open(desc);
        SSTableScanner scanner = reader.getScanner();

        IPartitioner<?> partitioner = reader.partitioner;

        if (excludes != null)
            toExport.removeAll(Arrays.asList(excludes));

        outs.println("[");

        int i = 0;

        // last key to compare order
        DecoratedKey lastKey = null;

        for (String key : toExport)
        {
            DecoratedKey decoratedKey = partitioner.decorateKey(hexToBytes(key));

            if (lastKey != null && lastKey.compareTo(decoratedKey) > 0)
                throw new IOException("Key out of order! " + lastKey + " > " + decoratedKey);

            lastKey = decoratedKey;

            scanner.seekTo(decoratedKey);

            if (!scanner.hasNext())
                continue;

            SSTableIdentityIterator row = (SSTableIdentityIterator) scanner.next();
            if (!row.getKey().equals(decoratedKey))
                continue;

            serializeRow(row, decoratedKey, outs);

            if (i != 0)
                outs.println(",");

            checkStream(outs);
            i++;
        }

        outs.println("\n]");
        outs.flush();

        scanner.close();
    }

    // This is necessary to accommodate the test suite since you cannot open a Reader more
    // than once from within the same process.
    static void export(SSTableReader reader, PrintStream outs, String[] excludes) throws IOException
    {
        Set<String> excludeSet = new HashSet<String>();

        if (excludes != null)
            excludeSet = new HashSet<String>(Arrays.asList(excludes));


        SSTableIdentityIterator row;
        SSTableScanner scanner = reader.getScanner();

        outs.println("[");

        int i = 0;

        // collecting keys to export
        while (scanner.hasNext())
        {
            row = (SSTableIdentityIterator) scanner.next();

            String currentKey = bytesToHex(row.getKey().key);

            if (excludeSet.contains(currentKey))
                continue;
            else if (i != 0)
                outs.println(",");

            serializeRow(row, row.getKey(), outs);
            checkStream(outs);

            i++;
        }

        outs.println("\n]");
        outs.flush();

        scanner.close();
    }

    /**
     * Export an SSTable and write the resulting JSON to a PrintStream.
     *
     * @param desc     the descriptor of the sstable table to read from
     * @param outs     PrintStream to write the output to
     * @param excludes keys to exclude from export
     * @throws IOException on failure to read/write input/output
     */
    public static void export(Descriptor desc, PrintStream outs, String[] excludes) throws IOException
    {
        export(SSTableReader.open(desc), outs, excludes);
    }

    /**
     * Export an SSTable and write the resulting JSON to standard out.
     *
     * @param desc     the descriptor of the sstable table to read from
     * @param excludes keys to exclude from export
     * @throws IOException on failure to read/write SSTable/standard out
     */
    public static void export(Descriptor desc, String[] excludes) throws IOException
    {
        export(desc, System.out, excludes);
    }

    /**
     * Given arguments specifying an SSTable, and optionally an output file,
     * export the contents of the SSTable to JSON.
     *
     * @param args command lines arguments
     * @throws IOException            on failure to open/read/write files or output streams
     * @throws ConfigurationException on configuration failure (wrong params given)
     */
    public static void main(String[] args) throws IOException, ConfigurationException
    {
        String usage = String.format("Usage: %s <sstable> [-k key [-k key [...]] -x key [-x key [...]]]%n", SSTableExport.class.getName());

        CommandLineParser parser = new PosixParser();
        try
        {
            cmd = parser.parse(options, args);
        }
        catch (ParseException e1)
        {
            System.err.println(e1.getMessage());
            System.err.println(usage);
            System.exit(1);
        }


        if (cmd.getArgs().length != 1)
        {
            System.err.println("You must supply exactly one sstable");
            System.err.println(usage);
            System.exit(1);
        }


        String[] keys = cmd.getOptionValues(KEY_OPTION);
        String[] excludes = cmd.getOptionValues(EXCLUDEKEY_OPTION);
        String ssTableFileName = new File(cmd.getArgs()[0]).getAbsolutePath();

        DatabaseDescriptor.loadSchemas();
        Descriptor descriptor = Descriptor.fromFilename(ssTableFileName);
        if (Schema.instance.getCFMetaData(descriptor) == null)
        {
            System.err.println(String.format("The provided column family is not part of this cassandra database: keysapce = %s, column family = %s",
                                             descriptor.ksname, descriptor.cfname));
            System.exit(1);
        }

        try
        {
            if (cmd.hasOption(ENUMERATEKEYS_OPTION))
            {
                enumeratekeys(descriptor, System.out);
            }
            else
            {
                if ((keys != null) && (keys.length > 0))
                    export(descriptor, System.out, Arrays.asList(keys), excludes);
                else
                    export(descriptor, excludes);
            }
        }
        catch (IOException e)
        {
            // throwing exception outside main with broken pipe causes windows cmd to hang
            e.printStackTrace(System.err);
        }

        System.exit(0);
    }

    private static void writeJSON(PrintStream out, Object value)
    {
        try
        {
            jsonMapper.writeValue(out, value);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
