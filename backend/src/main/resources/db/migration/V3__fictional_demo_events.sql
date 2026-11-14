-- Replace demo events that used real artists and brands with fictional ones,
-- and add a small "flash drop" event that sells out quickly under load.
-- (V2 is left untouched: applied migrations are never edited.)

UPDATE events SET
    title = 'Neon Tides: Stadium Tour',
    description = 'A fictional synth-pop act on its first stadium tour. Demo data.'
WHERE title = 'Coldplay: Music of the Spheres World Tour';

UPDATE ticket_tiers SET name = 'VIP Early Entry'
WHERE name = 'VIP Floris Early Entry';

UPDATE events SET
    title = 'CloudConf Sydney',
    description = 'A fictional two-day conference for people who build and run cloud systems. Demo data.'
WHERE title = 'AWS Cloud Tech Summit 2026';

UPDATE events SET
    title = 'Southern Circuit Motorsport Weekend',
    description = 'A fictional four-day motorsport festival around a lakeside street circuit. Demo data.',
    venue = 'Lakeside Street Circuit'
WHERE title = 'Formula 1 Australian Grand Prix';

UPDATE ticket_tiers SET name = 'Main Grandstand'
WHERE name = 'Brabham Grandstand';

INSERT INTO events (title, description, venue, city, category, image_url, start_time, end_time, status)
VALUES (
    'Midnight Drop: Rooftop Secret Show',
    'Only 50 tickets. Open two browser tabs and race yourself. Demo data.',
    'Rooftop at Pier 9',
    'Sydney',
    'Concert',
    'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?auto=format&fit=crop&w=1200&q=80',
    CURRENT_TIMESTAMP + INTERVAL '7 days',
    CURRENT_TIMESTAMP + INTERVAL '7 days 3 hours',
    'ACTIVE'
);

INSERT INTO ticket_tiers (event_id, name, price, total_capacity, available_capacity)
SELECT id, 'Flash Drop', 59.00, 50, 50
FROM events WHERE title = 'Midnight Drop: Rooftop Secret Show';
