COPY (
 SELECT id,
  s.doc_id,
  s.sentence_index,
  label,
  expectation,
  s.tokens,
  pm1.mention_text,
  pm2.mention_text,
  pm1.begin_index as p1_start,
  pm1.end_index-pm1.begin_index + 1 as p1_length,
  pm2.begin_index as p2_start,
  pm2.end_index-pm2.begin_index + 1 as p2_length,
  f.features,
  f.weights
FROM has_spouse_label_inference hsi,
  person_mention pm1,
  person_mention pm2,
  sentences s,
  ( SELECT p1_id,
      p2_id,
      ARRAY_AGG(feature ORDER BY abs(weight) DESC) AS features,
      ARRAY_AGG(weight ORDER BY abs(weight) DESC) AS weights
    FROM dd_weights_inf_istrue_has_spouse dd1,
      dd_inference_result_weights dd2,
      spouse_feature
    WHERE feature = dd_weight_column_0
      AND dd1.id = dd2.id
    GROUP BY p1_id,p2_id
  ) f
WHERE hsi.p1_id = pm1.mention_id
  AND pm1.doc_id = s.doc_id
  AND pm1.sentence_index = s.sentence_index
  AND hsi.p2_id = pm2.mention_id
  AND pm2.doc_id = s.doc_id
  AND pm2.sentence_index = s.sentence_index
  AND hsi.p1_id = f.p1_id
  AND hsi.p2_id = f.p2_id
  AND expectation > 0.9
 ORDER BY random() LIMIT 100
) TO STDOUT WITH CSV HEADER;
