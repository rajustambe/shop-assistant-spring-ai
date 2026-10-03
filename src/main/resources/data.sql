-- Seed data loaded at startup (after Hibernate creates the schema).
INSERT INTO orders (id, customer_name, item, status, city) VALUES
 (1001, 'Asha',   'Wireless Headphones', 'SHIPPED',    'Pune'),
 (1002, 'Ravi',   'Yoga Mat',            'PROCESSING', 'Mumbai'),
 (1003, 'Meera',  'Coffee Grinder',      'DELIVERED',  'Bengaluru'),
 (1004, 'Sanjay', 'Desk Lamp',           'CANCELLED',  'Delhi');
