/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.mirromutth.r2dbc.mysql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.mirromutth.r2dbc.mysql.internal.AssertUtils.requireNonNull;

/**
 * Utility for parse {@link Query} from sql and format {@code Batch} element statement.
 */
final class Queries {

    static Query parse(String sql) {
        requireNonNull(sql, "sql must not be null");

        int offset = findParamMark(sql, 0);

        if (offset < 0) {
            // No parameter mark, it must be simple query.
            return new SimpleQuery(sql);
        } else {
            // Find parameter mark '?', it should be prepare query.
            return parsePrepare(sql, offset);
        }
    }

    /**
     * Parse parameter names of a parametrized SQL, and remove parameter names for parsed SQL which will be
     * send to MySQL server directly. The relationship between parameter names and parameter indexes will
     * be recorded by {@code nameKeyedParams}.
     * <p>
     * All parameters will be counted by {@code paramCount} even it has no name or has the same name of
     * other parameter.
     * <p>
     * For example:
     * {@code SELECT * FROM `test` WHERE (`username` = ?name OR `nickname` = ?name) AND `group` = ?} will
     * parse to {@code SELECT * FROM `test` WHERE `username` = ? OR `nickname` = ? AND `group` = ?}, and
     * mapped {@literal name} to {@literal 0} and {@literal 1}, {@code paramCount} will be {@literal 3}.
     *
     * @param sql    the statement want to parse, must contains least one parameter mark.
     * @param offset first '?' offset
     * @return parsed {@link PrepareQuery}
     */
    private static PrepareQuery parsePrepare(String sql, int offset) {
        Map<String, List<Integer>> nameKeyedParams = new HashMap<>();
        SqlBuilder sqlBuilder = new SqlBuilder(sql);
        String anyName = null;
        int length = sql.length();
        int paramCount = 0;

        while (offset >= 0 && offset < length) {
            ++paramCount;
            ++offset;

            if (offset < length) {
                char now = sql.charAt(offset);

                // Java style parameter name follow the '?'.
                if (Character.isJavaIdentifierStart(now)) {
                    int start = offset++;

                    while (offset < length) {
                        if (!Character.isJavaIdentifierPart(sql.charAt(offset))) {
                            break;
                        }

                        ++offset;
                    }

                    String name = sqlBuilder.parameter(start, offset);
                    int paramIndex = paramCount - 1;

                    anyName = name;

                    if (nameKeyedParams.containsKey(name)) {
                        List<Integer> value = nameKeyedParams.get(name);
                        value.add(paramIndex);
                    } else {
                        List<Integer> value = new ArrayList<>();
                        value.add(paramIndex);
                        nameKeyedParams.put(name, value);
                    }
                }
            } // offset is length or end of a parameter.

            if (offset < length) {
                offset = findParamMark(sql, offset);
            }
        }

        String parsedSql = sqlBuilder.toString();
        int mapSize = nameKeyedParams.size();

        if (anyName == null || mapSize == 0) {
            return new PrepareQuery(parsedSql, Collections.emptyMap(), paramCount);
        }

        if (mapSize == 1) {
            return new PrepareQuery(parsedSql, Collections.singletonMap(anyName, convert(nameKeyedParams.get(anyName))), paramCount);
        }

        // ceil(size / 0.75) = ceil((size * 4) / 3) = floor((size * 4 + 3 - 1) / 3)
        Map<String, int[]> indexesMap = new HashMap<>(((mapSize << 2) + 2) / 3, 0.75f);

        for (Map.Entry<String, List<Integer>> entry : nameKeyedParams.entrySet()) {
            List<Integer> value = entry.getValue();

            if (value != null && !value.isEmpty()) {
                indexesMap.put(entry.getKey(), convert(value));
            }
        }

        return new PrepareQuery(parsedSql, indexesMap, paramCount);
    }

    /**
     * Locates the first occurrence of {@literal ?} return true in {@code sql} starting at {@code offset}.
     * <p>
     * The SQL string may contain:
     *
     * <ul>
     * <li>Literals, enclosed in single quotes ({@literal '}) </li>
     * <li>Literals, enclosed in double quotes ({@literal "}) </li>
     * <li>Literals, enclosed in backtick quotes ({@literal `}) </li>
     * <li>Escaped escapes or literal delimiters (i.e. {@literal ''}, {@literal ""} or {@literal ``)</li>
     * <li>Single-line comments beginning with {@literal --}</li>
     * <li>Multi-line comments beginning enclosed</li>
     * </ul>
     *
     * @param sql    the SQL string to search in.
     * @param offset the offset to start searching.
     * @return the offset or a negative integer if not found.
     */
    private static int findParamMark(CharSequence sql, int offset) {
        int length = sql.length();
        char ch;

        while (offset < length && offset >= 0) {
            ch = sql.charAt(offset++);
            switch (ch) {
                case '/':
                    if (offset == length) {
                        break;
                    }

                    if (sql.charAt(offset) == '*') {
                        // Consume if '/* ... */' comment.
                        while (++offset < length) {
                            if (sql.charAt(offset) == '*' && offset + 1 < length && sql.charAt(offset + 1) == '/') {
                                // If end of comment.
                                offset += 2;
                                break;
                            }
                        }
                        break;
                    }

                    break;
                case '-':
                    if (offset == length) {
                        break;
                    }

                    if (sql.charAt(offset) == '-') {
                        // Consume if '-- ... \n' comment.
                        while (++offset < length) {
                            char now = sql.charAt(offset);
                            if (now == '\n' || now == '\r') {
                                // If end of comment
                                offset++;
                                break;
                            }
                        }
                        break;
                    }

                    break;
                case '`':
                case '\'':
                case '"':
                    // Quote cases, should find same quote
                    while (offset < length) {
                        if (sql.charAt(offset++) == ch) {
                            if (length == offset || sql.charAt(offset) != ch) {
                                break;
                            }

                            ++offset;
                        }
                    }

                    break;
                default:
                    if (ch == '?') {
                        return offset - 1;
                    }

                    break;
            }
        }

        return -1;
    }

    private static int[] convert(List<Integer> indexes) {
        int size = indexes.size();
        int[] result = new int[size];

        for (int i = 0; i < size; ++i) {
            result[i] = indexes.get(i);
        }

        return result;
    }

    private static final class SqlBuilder {

        private final String sql;

        private int lastEnd = 0;

        private StringBuilder builder;

        private SqlBuilder(String sql) {
            this.sql = sql;
        }

        private String parameter(int start, int end) {
            getBuilder().append(sql, lastEnd, start);
            lastEnd = end;
            return sql.substring(start, end);
        }

        private StringBuilder getBuilder() {
            if (builder == null) {
                builder = new StringBuilder(sql.length());
            }

            return builder;
        }

        @Override
        public String toString() {
            if (builder == null) {
                return sql;
            }

            int length = sql.length();

            if (lastEnd < length) {
                return builder.append(sql, lastEnd, length).toString();
            }

            return builder.toString();
        }
    }

    private Queries() {
    }
}
