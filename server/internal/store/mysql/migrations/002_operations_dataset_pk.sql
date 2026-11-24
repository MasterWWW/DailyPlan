ALTER TABLE operations
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (dataset_id, op_id);
